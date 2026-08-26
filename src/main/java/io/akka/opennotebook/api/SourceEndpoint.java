package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.opennotebook.ai.AiClient;
import io.akka.opennotebook.application.DefaultModelsEntity;
import io.akka.opennotebook.application.NoteEntity;
import io.akka.opennotebook.application.NotebookEntity;
import io.akka.opennotebook.application.SourceEntity;
import io.akka.opennotebook.application.SourceIngestionWorkflow;
import io.akka.opennotebook.application.TransformationEntity;
import io.akka.opennotebook.domain.DefaultModels;
import io.akka.opennotebook.domain.ExtractionRequest;
import io.akka.opennotebook.domain.Note;
import io.akka.opennotebook.domain.NoteType;
import io.akka.opennotebook.domain.Source;
import io.akka.opennotebook.domain.Transformation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@HttpEndpoint("/sources")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class SourceEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(String type, String content, String url, String title, List<String> notebooks) {}

  public record SourceResponse(
      String sourceId,
      String title,
      String status,
      String errorMessage,
      String fullText,
      List<String> notebookIds,
      List<io.akka.opennotebook.domain.Insight> insights) {}

  public record AddInsightRequest(String insightType, String content) {}

  public record SaveAsNoteRequest(String notebookId) {}

  public record NoteResponse(String noteId, String title, String content, String noteType) {}

  public record GenerateInsightRequest(String transformationId, String modelId) {}

  private final ComponentClient componentClient;
  private final AiClient aiClient;

  public SourceEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
    this.aiClient = new AiClient(componentClient);
  }

  @Post("")
  public HttpResponse create(CreateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    List<String> notebooks = request.notebooks() == null ? List.of() : request.notebooks();

    ExtractionRequest extractionRequest;
    String url = null;
    if ("text".equals(request.type())) {
      if (request.content() == null || request.content().isBlank()) {
        return HttpResponses.badRequest("Content is required for text type");
      }
      extractionRequest = new ExtractionRequest.PlainText(request.content());
    } else if ("link".equals(request.type())) {
      if (request.url() == null || request.url().isBlank()) {
        return HttpResponses.badRequest("URL is required for link type");
      }
      url = request.url();
      extractionRequest = new ExtractionRequest.Url(request.url());
    } else {
      return HttpResponses.badRequest("Invalid source type. Must be link or text");
    }

    String sourceId = UUID.randomUUID().toString();

    // R1: the source exists, in NEW status and linked to every notebook, before extraction runs.
    componentClient
        .forEventSourcedEntity(sourceId)
        .method(SourceEntity::createPlaceholder)
        .invoke(
            new SourceEntity.CreatePlaceholder(
                request.title(), url, null, notebooks, Instant.now()));

    for (String notebookId : notebooks) {
      componentClient
          .forEventSourcedEntity(notebookId)
          .method(NotebookEntity::linkSource)
          .invoke(new NotebookEntity.SourceLinked(sourceId, Instant.now()));
    }

    componentClient
        .forWorkflow(sourceId)
        .method(SourceIngestionWorkflow::start)
        .invoke(SourceIngestionWorkflow.Start.of(sourceId, extractionRequest));

    return HttpResponses.created(toApi(fetch(sourceId)), "/sources/" + sourceId);
  }

  @Get("/{sourceId}")
  public HttpResponse get(String sourceId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source source = fetch(sourceId);
    if (source == null) {
      return HttpResponses.notFound("Source not found");
    }
    return HttpResponses.ok(toApi(source));
  }

  @Post("/{sourceId}/insights")
  public HttpResponse addInsight(String sourceId, AddInsightRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient
          .forEventSourcedEntity(sourceId)
          .method(SourceEntity::addInsight)
          .invoke(
              new SourceEntity.AddInsight(request.insightType(), request.content(), Instant.now()));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
    return HttpResponses.ok(toApi(fetch(sourceId)));
  }

  /**
   * Runs a saved {@link Transformation}'s prompt against this source's text via {@link AiClient}
   * and records the model's reply as a new insight — the LLM call itself, in scope per SPEC-001
   * §Transformations (previously excluded as a "different capability"; it is not).
   */
  @Post("/{sourceId}/insights/generate")
  public HttpResponse generateInsight(String sourceId, GenerateInsightRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source source = fetch(sourceId);
    if (source == null) {
      return HttpResponses.notFound("Source not found");
    }
    if (source.fullText() == null || source.fullText().isBlank()) {
      return HttpResponses.badRequest("Source has no extracted text to transform");
    }
    Transformation transformation;
    try {
      transformation =
          componentClient
              .forKeyValueEntity(request.transformationId())
              .method(TransformationEntity::get)
              .invoke();
    } catch (Exception e) {
      return HttpResponses.badRequest("Transformation not found");
    }

    String modelId = request.modelId() != null ? request.modelId() : transformation.modelId();
    if (modelId == null) {
      DefaultModels defaults =
          componentClient
              .forKeyValueEntity(DefaultModelsEntity.ID)
              .method(DefaultModelsEntity::get)
              .invoke();
      modelId = defaults.defaultTransformationModel();
    }

    String systemPrompt =
        transformation.prompt()
            + "\n\n# MATH FORMATTING\n\nWhen showing math, write display math as $$...$$ and "
            + "inline math as $...$ so formulas render properly.\n\n# INPUT";
    String content;
    try {
      content =
          aiClient.chatComplete(
              modelId, systemPrompt, List.of(new AiClient.ChatMessage("user", source.fullText())));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }

    componentClient
        .forEventSourcedEntity(sourceId)
        .method(SourceEntity::addInsight)
        .invoke(new SourceEntity.AddInsight(transformation.title(), content, Instant.now()));

    return HttpResponses.ok(toApi(fetch(sourceId)));
  }

  /** R14: turn a source's insight into an AI note, linked to the caller's notebook. */
  @Post("/{sourceId}/insights/{insightIndex}/save-as-note")
  public HttpResponse saveInsightAsNote(String sourceId, int insightIndex, SaveAsNoteRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source source = fetch(sourceId);
    if (source == null) {
      return HttpResponses.notFound("Source not found");
    }
    if (insightIndex < 0 || insightIndex >= source.insights().size()) {
      return HttpResponses.badRequest("No such insight");
    }
    var insight = source.insights().get(insightIndex);
    String noteId = UUID.randomUUID().toString();
    Note note = Note.fromInsight(noteId, source.title(), insight, request.notebookId(), Instant.now());

    componentClient
        .forEventSourcedEntity(noteId)
        .method(NoteEntity::create)
        .invoke(
            new NoteEntity.Create(
                note.title(), note.content(), NoteType.AI, List.of(request.notebookId()), Instant.now()));

    componentClient
        .forEventSourcedEntity(request.notebookId())
        .method(NotebookEntity::linkNote)
        .invoke(new NotebookEntity.NoteLinked(noteId, Instant.now()));

    var created = componentClient.forEventSourcedEntity(noteId).method(NoteEntity::get).invoke();
    return HttpResponses.ok(
        new NoteResponse(created.noteId(), created.title(), created.content(), created.noteType().name()));
  }

  private Source fetch(String sourceId) {
    try {
      return componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }

  private SourceResponse toApi(Source source) {
    return new SourceResponse(
        source.sourceId(),
        source.title(),
        source.status().name(),
        source.errorMessage(),
        source.fullText(),
        List.copyOf(source.notebookIds()),
        List.copyOf(source.insights()));
  }
}
