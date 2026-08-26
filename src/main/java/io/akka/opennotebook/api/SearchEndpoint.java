package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
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
 * Embedding and search (SPEC-001 §Search) — chunk, embed, and a full linear cosine-similarity
 * scan over {@link EmbeddingsView} in place of the source's indexed {@code vector_search}/
 * {@code text_search} (see {@link EmbeddingsView}'s class doc for the divergence).
 */
@HttpEndpoint("")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class SearchEndpoint extends AbstractHttpEndpoint {

  public record VectorizeRequest(String modelId) {}

  public record VectorizeResponse(String ownerId, int chunkCount) {}

  public record SearchRequest(String query, String modelId, int limit) {}

  public record SearchResult(String ownerType, String ownerId, int chunkIndex, String text, double score) {}

  public record SearchResponse(List<SearchResult> results) {}

  private final ComponentClient componentClient;
  private final AiClient aiClient;

  public SearchEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
    this.aiClient = new AiClient(componentClient);
  }

  @Post("/sources/{sourceId}/vectorize")
  public HttpResponse vectorizeSource(String sourceId, VectorizeRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source source;
    try {
      source = componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
    } catch (Exception e) {
      return HttpResponses.notFound("Source not found");
    }
    if (source.fullText() == null || source.fullText().isBlank()) {
      return HttpResponses.badRequest("Source has no extracted text to vectorize");
    }
    try {
      int count = vectorize("source", sourceId, source.fullText(), resolveEmbeddingModel(request.modelId()));
      return HttpResponses.ok(new VectorizeResponse(sourceId, count));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Post("/notes/{noteId}/vectorize")
  public HttpResponse vectorizeNote(String noteId, VectorizeRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Note note;
    try {
      note = componentClient.forEventSourcedEntity(noteId).method(NoteEntity::get).invoke();
    } catch (Exception e) {
      return HttpResponses.notFound("Note not found");
    }
    if (note.content() == null || note.content().isBlank()) {
      return HttpResponses.badRequest("Note has no content to vectorize");
    }
    try {
      int count = vectorize("note", noteId, note.content(), resolveEmbeddingModel(request.modelId()));
      return HttpResponses.ok(new VectorizeResponse(noteId, count));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Post("/search")
  public HttpResponse search(SearchRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    if (request.query() == null || request.query().isBlank()) {
      return HttpResponses.badRequest("Query cannot be empty");
    }
    String modelId = resolveEmbeddingModel(request.modelId());
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
            .map(
                e ->
                    new SearchResult(
                        e.ownerType(), e.ownerId(), e.chunkIndex(), e.text(),
                        Chunking.cosineSimilarity(queryList, e.vector())))
            .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
            .limit(limit)
            .toList();
    return HttpResponses.ok(new SearchResponse(ranked));
  }

  private int vectorize(String ownerType, String ownerId, String text, String modelId) {
    // Re-vectorizing with fewer chunks than a previous pass must not leave the tail of the old
    // chunk set behind as stale, still-searchable rows.
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

  private String resolveEmbeddingModel(String explicit) {
    if (explicit != null && !explicit.isBlank()) return explicit;
    DefaultModels defaults =
        componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::get).invoke();
    return defaults.defaultEmbeddingModel();
  }
}
