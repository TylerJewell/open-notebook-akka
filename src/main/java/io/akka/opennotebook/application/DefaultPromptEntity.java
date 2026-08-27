package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/**
 * The server-wide default transformation prompt ({@code GET/PUT /api/transformations/default-
 * prompt}) -- a setting the bare-path {@code TransformationEndpoint} never exposed because the
 * original slice didn't need it. Singleton entity, always addressed at id {@code "default"},
 * matching {@link DefaultModelsEntity}'s and {@link ContentSettingsEntity}'s own pattern.
 */
@Component(id = "default-prompt")
public class DefaultPromptEntity extends KeyValueEntity<String> {

  public static final String ID = "default";

  public static final String DEFAULT =
      "You will be given a document and a specific instruction. Follow the instruction precisely "
          + "and return only the requested content, based strictly on the document provided.";

  @Override
  public String emptyState() {
    return DEFAULT;
  }

  public Effect<String> set(String command) {
    String value = (command == null || command.isBlank()) ? DEFAULT : command;
    return effects().updateState(value).thenReply(value);
  }

  public ReadOnlyEffect<String> get() {
    return effects().reply(currentState());
  }
}
