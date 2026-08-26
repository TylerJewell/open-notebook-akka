package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;
import io.akka.opennotebook.domain.ModelRecord;
import java.time.Instant;

/** A provisioned model, registered against a provider and (optionally) a credential. */
@Component(id = "model")
public class ModelEntity extends KeyValueEntity<ModelRecord> {

  public record Create(String name, String provider, String type, String credentialId, Instant now) {}

  private final String id;

  public ModelEntity(KeyValueEntityContext context) {
    this.id = context.entityId();
  }

  @Override
  public ModelRecord emptyState() {
    return ModelRecord.empty();
  }

  public Effect<ModelRecord> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Model already exists");
    }
    if (command.name() == null || command.name().isBlank()) {
      return effects().error("Model name cannot be empty");
    }
    if (!ModelRecord.TYPE_LANGUAGE.equals(command.type())
        && !ModelRecord.TYPE_EMBEDDING.equals(command.type())
        && !ModelRecord.TYPE_TEXT_TO_SPEECH.equals(command.type())
        && !ModelRecord.TYPE_SPEECH_TO_TEXT.equals(command.type())) {
      return effects().error("Invalid model type: " + command.type());
    }
    ModelRecord created =
        ModelRecord.create(
            id, command.name(), command.provider(), command.type(), command.credentialId(), command.now());
    return effects().updateState(created).thenReply(created);
  }

  public Effect<String> delete() {
    if (!currentState().exists()) {
      return effects().error("Model not found");
    }
    return effects().deleteEntity().thenReply("deleted");
  }

  public ReadOnlyEffect<ModelRecord> get() {
    if (!currentState().exists()) {
      return effects().error("Model not found");
    }
    return effects().reply(currentState());
  }
}
