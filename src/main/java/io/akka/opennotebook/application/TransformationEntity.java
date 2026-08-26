package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;
import io.akka.opennotebook.domain.Transformation;
import java.time.Instant;

@Component(id = "transformation")
public class TransformationEntity extends KeyValueEntity<Transformation> {

  public record Create(
      String name, String title, String description, String prompt, boolean applyDefault, String modelId, Instant now) {}

  private final String id;

  public TransformationEntity(KeyValueEntityContext context) {
    this.id = context.entityId();
  }

  @Override
  public Transformation emptyState() {
    return Transformation.empty();
  }

  public Effect<Transformation> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Transformation already exists");
    }
    if (command.name() == null || command.name().isBlank()) {
      return effects().error("Transformation name cannot be empty");
    }
    if (command.prompt() == null || command.prompt().isBlank()) {
      return effects().error("Transformation prompt cannot be empty");
    }
    Transformation created =
        Transformation.create(
            id, command.name(), command.title(), command.description(), command.prompt(),
            command.applyDefault(), command.modelId(), command.now());
    return effects().updateState(created).thenReply(created);
  }

  public Effect<String> delete() {
    if (!currentState().exists()) {
      return effects().error("Transformation not found");
    }
    return effects().deleteEntity().thenReply("deleted");
  }

  public ReadOnlyEffect<Transformation> get() {
    if (!currentState().exists()) {
      return effects().error("Transformation not found");
    }
    return effects().reply(currentState());
  }
}
