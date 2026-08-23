package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import io.akka.opennotebook.application.NoteEntity;
import io.akka.opennotebook.application.NotebookEntity;
import io.akka.opennotebook.application.SourceEntity;
import io.akka.opennotebook.application.SourceIngestionWorkflow;
import io.akka.opennotebook.domain.ExtractionRequest;
import io.akka.opennotebook.domain.Note;
import io.akka.opennotebook.domain.NoteType;
import io.akka.opennotebook.domain.Source;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@HttpEndpoint("/sources")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class SourceEndpoint {

  public record CreateRequest(String type, String content, String url, String title, List<String> notebooks) {}

  public record SourceResponse(
      String sourceId,
      String title,
      String status,
      String errorMessage,
      String fullText,
      List<String> notebookIds) {}

  public record AddInsightRequest(String insightType, String content) {}

  public record SaveAsNoteRequest(String notebookId) {}

  public record NoteResponse(String noteId, String title, String content, String noteType) {}

  private final ComponentClient componentClient;

  public SourceEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("")
  public HttpResponse create(CreateRequest request) {
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
    Source source = fetch(sourceId);
    if (source == null) {
      return HttpResponses.notFound("Source not found");
    }
    return HttpResponses.ok(toApi(source));
  }

  @Post("/{sourceId}/insights")
  public HttpResponse addInsight(String sourceId, AddInsightRequest request) {
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

  /** R14: turn a source's insight into an AI note, linked to the caller's notebook. */
  @Post("/{sourceId}/insights/{insightIndex}/save-as-note")
  public HttpResponse saveInsightAsNote(String sourceId, int insightIndex, SaveAsNoteRequest request) {
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
        List.copyOf(source.notebookIds()));
  }
}
