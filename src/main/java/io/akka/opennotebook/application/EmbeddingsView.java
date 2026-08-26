package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.opennotebook.domain.EmbeddingChunk;
import java.util.List;

/**
 * Every embedded chunk (SPEC-001 §Search). Ranking is done in {@code SearchEndpoint} after a
 * full-table read here, not in SurrealQL's {@code vector_search}/{@code text_search} — a linear
 * cosine scan over a View's rows, not an HNSW index. Documented divergence: correct at this
 * port's scale, not the source's indexed-search performance.
 */
@Component(id = "embeddings-view")
public class EmbeddingsView extends View {

  public record Entry(String id, String ownerType, String ownerId, int chunkIndex, String text, List<Double> vector) {}

  public record Entries(List<Entry> items) {}

  @Consume.FromKeyValueEntity(EmbeddingChunkEntity.class)
  public static class Chunks extends TableUpdater<Entry> {
    public Effect<Entry> onUpdate(EmbeddingChunk state) {
      if (!state.exists()) return effects().deleteRow();
      return effects()
          .updateRow(
              new Entry(state.id(), state.ownerType(), state.ownerId(), state.chunkIndex(), state.text(), state.vector()));
    }
  }

  @Query("SELECT * AS items FROM chunks ORDER BY ownerId")
  public QueryEffect<Entries> all() {
    return queryResult();
  }

  @Query("SELECT * AS items FROM chunks WHERE ownerId = :ownerId ORDER BY chunkIndex")
  public QueryEffect<Entries> byOwner(String ownerId) {
    return queryResult();
  }
}
