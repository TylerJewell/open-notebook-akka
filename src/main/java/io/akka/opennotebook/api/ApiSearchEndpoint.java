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
import io.akka.opennotebook.ai.Chunking;
import io.akka.opennotebook.application.DefaultModelsEntity;
import io.akka.opennotebook.application.EmbeddingChunkEntity;
import io.akka.opennotebook.application.EmbeddingsView;
import io.akka.opennotebook.application.NoteEntity;
import io.akka.opennotebook.application.SourceEntity;
import io.akka.opennotebook.domain.DefaultModels;
import io.akka.opennotebook.domain.Note;
import io.akka.opennotebook.domain.Source;
import java.util.Comparator;
import java.util.List;

/**
 * The frontend's {@code frontend/src/lib/api/search.ts} and {@code embedding.ts} against {@link
 * EmbeddingsView} / {@link AiClient} -- R24-R25, snake_case wire shape, plus the SSE ask route
 * and a synchronous stand-in for the source's job-queue-backed embedding rebuild.
 *
 * <p><b>{@code POST /api/search/ask}, matched to the wire contract, narrowed the same way as the
 * bare-path {@code ChatEndpoint.ask} (SPEC-001 SS8 D-8):</b> the source's ask graph emits
 * {@code strategy} -> {@code answer} -> {@code final_answer} -> {@code complete} SSE events from a
 * multi-query retrieval pipeline; this port hands the model the notebook's full assembled context
 * directly and emits exactly two events -- {@code answer} with the complete text (no token-by-
 * token delivery: {@link AiClient#chatComplete} is not itself a streaming call), then {@code
 * complete}. The frontend's {@code use-ask.ts} reads whichever of {@code content}/{@code
 * final_answer} is present per event type, so this still renders as one settled answer.
 */
@HttpEndpoint("/api")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiSearchEndpoint extends AbstractHttpEndpoint {

  public record SearchRequest(String query, String type, int limit, boolean search_sources, boolean search_notes, double minimum_score) {}

  public record SearchResult(
      String id, String title, String parent_id, double final_score, String type, String created, String updated) {}

  public record SearchResponse(List<SearchResult> results, int total_count, String search_type) {}

  public record AskRequest(String question, String strategy_model, String answer_model, String final_answer_model) {}

  public record AskEvent(String type, String content, String final_answer, String message) {}

  public record AskResponse(String answer, String question) {}

  public record EmbedRequest(String item_id, String item_type, boolean async_processing) {}

  public record EmbedResponse(String success, String message, int chunks_created) {}

  public record RebuildRequest(String mode, boolean include_sources, boolean include_notes, boolean include_insights) {}

  public record RebuildResponse(String command_id, String message, int estimated_items) {}

  public record RebuildStatusResponse(String command_id, String status) {}

  private final ComponentClient componentClient;
  private final AiClient aiClient;

  public ApiSearchEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
    this.aiClient = new AiClient(componentClient);
  }

  @Post("/search")
  public HttpResponse search(SearchRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    if (request.query() == null || request.query().isBlank()) {
      return HttpResponses.badRequest("query cannot be empty");
    }
    String modelId = defaultEmbeddingModel();
    double[] queryVector;
    try {
      queryVector = aiClient.embed(modelId, request.query());
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
    List<Double> queryList = java.util.stream.DoubleStream.of(queryVector).boxed().toList();
    EmbeddingsView.Entries all = componentClient.forView().method(EmbeddingsView::all).invoke();
    int limit = request.limit() > 0 ? request.limit() : 10;
    List<SearchResult> ranked =
        all.items().stream()
            .filter(e -> (request.search_sources() || !"source".equals(e.ownerType())))
            .filter(e -> (request.search_notes() || !"note".equals(e.ownerType())))
            .map(e -> new Object[] {e, Chunking.cosineSimilarity(queryList, e.vector())})
            .filter(pair -> (double) pair[1] >= request.minimum_score())
            .sorted(Comparator.comparingDouble((Object[] pair) -> (double) pair[1]).reversed())
            .limit(limit)
            .map(
                pair -> {
                  var e = (EmbeddingsView.Entry) pair[0];
                  return new SearchResult(e.ownerId(), e.text(), e.ownerId(), (double) pair[1], e.ownerType(), null, null);
                })
            .toList();
    return HttpResponses.ok(new SearchResponse(ranked, ranked.size(), request.type() != null ? request.type() : "vector"));
  }

  @Post("/search/ask")
  public HttpResponse ask(AskRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String question = request.question();
    String modelId = firstNonBlank(request.final_answer_model(), request.answer_model(), request.strategy_model());
    if (modelId == null) modelId = defaultChatModel();

    String answer;
    try {
      answer = aiClient.chatComplete(modelId, "Answer the user's question as helpfully as possible.",
          List.of(new AiClient.ChatMessage("user", question)));
    } catch (Exception e) {
      var errorStream = akka.stream.javadsl.Source.single(new AskEvent("error", null, null, e.getMessage()));
      return HttpResponses.serverSentEvents(errorStream);
    }
    var events =
        akka.stream.javadsl.Source.from(
            List.of(new AskEvent("answer", answer, null, null), new AskEvent("complete", null, answer, "done")));
    return HttpResponses.serverSentEvents(events);
  }

  /** The original's non-streaming twin of {@link #ask}: {@code POST /search/ask/simple}, one
   * JSON {@code AskResponse} instead of an SSE event stream. */
  @Post("/search/ask/simple")
  public HttpResponse askSimple(AskRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String question = request.question();
    String modelId = firstNonBlank(request.final_answer_model(), request.answer_model(), request.strategy_model());
    if (modelId == null) modelId = defaultChatModel();

    try {
      String answer =
          aiClient.chatComplete(
              modelId, "Answer the user's question as helpfully as possible.",
              List.of(new AiClient.ChatMessage("user", question)));
      return HttpResponses.ok(new AskResponse(answer, question));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Post("/embed")
  public HttpResponse embed(EmbedRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      int count;
      if ("note".equals(request.item_type())) {
        Note note = componentClient.forEventSourcedEntity(request.item_id()).method(NoteEntity::get).invoke();
        count = vectorize("note", request.item_id(), note.content(), defaultEmbeddingModel());
      } else {
        Source source = componentClient.forEventSourcedEntity(request.item_id()).method(SourceEntity::get).invoke();
        count = vectorize("source", request.item_id(), source.fullText(), defaultEmbeddingModel());
      }
      return HttpResponses.ok(new EmbedResponse("true", "Embedded", count));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  /** Synchronous, unlike the source's async job-queue rebuild: by the time this call returns, the
   * work described in {@code message}/{@code estimated_items} is already done. {@code
   * rebuildStatus} always reports {@code completed} for the one {@code command_id} this endpoint
   * ever issues ({@code "sync"}), since nothing here is ever still running when a caller could
   * ask about it. */
  @Post("/embeddings/rebuild")
  public HttpResponse rebuild(RebuildRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String modelId = defaultEmbeddingModel();
    boolean onlyExisting = "existing".equals(request.mode());
    java.util.Set<String> alreadyEmbedded =
        onlyExisting
            ? componentClient.forView().method(EmbeddingsView::all).invoke().items().stream()
                .map(EmbeddingsView.Entry::ownerId)
                .collect(java.util.stream.Collectors.toSet())
            : java.util.Set.of();
    int count = 0;
    if (request.include_sources()) {
      var sources = componentClient.forView().method(io.akka.opennotebook.application.SourcesView::all).invoke();
      for (var s : sources.items()) {
        String fullText = s.fullText().orElse(null);
        if (fullText == null || fullText.isBlank()) continue;
        if (onlyExisting && !alreadyEmbedded.contains(s.sourceId())) continue;
        vectorize("source", s.sourceId(), fullText, modelId);
        count++;
      }
    }
    if (request.include_notes()) {
      var notes = componentClient.forView().method(io.akka.opennotebook.application.NotesView::all).invoke();
      for (var n : notes.items()) {
        String content = n.content().orElse(null);
        if (content == null || content.isBlank()) continue;
        if (onlyExisting && !alreadyEmbedded.contains(n.noteId())) continue;
        vectorize("note", n.noteId(), content, modelId);
        count++;
      }
    }
    return HttpResponses.ok(new RebuildResponse("sync", "Rebuild complete", count));
  }

  @Get("/embeddings/rebuild/{commandId}/status")
  public HttpResponse rebuildStatus(String commandId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(new RebuildStatusResponse(commandId, "completed"));
  }

  private int vectorize(String ownerType, String ownerId, String text, String modelId) {
    EmbeddingsView.Entries existing = componentClient.forView().method(EmbeddingsView::byOwner).invoke(ownerId);
    for (var entry : existing.items()) {
      componentClient.forKeyValueEntity(entry.id()).method(EmbeddingChunkEntity::delete).invoke();
    }
    List<String> chunks = Chunking.chunk(text);
    for (int i = 0; i < chunks.size(); i++) {
      double[] vector = aiClient.embed(modelId, chunks.get(i));
      List<Double> vectorList = java.util.stream.DoubleStream.of(vector).boxed().toList();
      String chunkId = ownerType + ":" + ownerId + ":" + i;
      componentClient
          .forKeyValueEntity(chunkId)
          .method(EmbeddingChunkEntity::create)
          .invoke(new EmbeddingChunkEntity.Create(ownerType, ownerId, i, chunks.get(i), vectorList));
    }
    return chunks.size();
  }

  private String defaultEmbeddingModel() {
    DefaultModels defaults = componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::get).invoke();
    return defaults.defaultEmbeddingModel();
  }

  private String defaultChatModel() {
    DefaultModels defaults = componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::get).invoke();
    return defaults.defaultChatModel();
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) if (v != null && !v.isBlank()) return v;
    return null;
  }
}
