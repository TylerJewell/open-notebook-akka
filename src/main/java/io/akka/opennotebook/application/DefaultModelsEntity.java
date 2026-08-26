package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.opennotebook.domain.DefaultModels;

/** Singleton entity, always addressed at id {@code "default"} — one server-wide record. */
@Component(id = "default-models")
public class DefaultModelsEntity extends KeyValueEntity<DefaultModels> {

  public static final String ID = "default";

  @Override
  public DefaultModels emptyState() {
    return DefaultModels.empty();
  }

  public Effect<DefaultModels> set(DefaultModels command) {
    return effects().updateState(command).thenReply(command);
  }

  public ReadOnlyEffect<DefaultModels> get() {
    return effects().reply(currentState());
  }
}
