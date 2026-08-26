package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;
import io.akka.opennotebook.domain.EmbeddingChunk;
import java.util.List;

@Component(id = "embedding-chunk")
public class EmbeddingChunkEntity extends KeyValueEntity<EmbeddingChunk> {

  public record Create(String ownerType, String ownerId, int chunkIndex, String text, List<Double> vector) {}

  private final String id;

  public EmbeddingChunkEntity(KeyValueEntityContext context) {
    this.id = context.entityId();
  }

  @Override
  public EmbeddingChunk emptyState() {
    return EmbeddingChunk.empty();
  }

  public Effect<EmbeddingChunk> create(Create command) {
    EmbeddingChunk created =
        EmbeddingChunk.create(
            id, command.ownerType(), command.ownerId(), command.chunkIndex(), command.text(), command.vector());
    return effects().updateState(created).thenReply(created);
  }

  public Effect<String> delete() {
    return effects().deleteEntity().thenReply("deleted");
  }

  public ReadOnlyEffect<EmbeddingChunk> get() {
    if (!currentState().exists()) {
      return effects().error("Chunk not found");
    }
    return effects().reply(currentState());
  }
}
