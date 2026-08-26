package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.opennotebook.domain.ContentSettings;

/** Singleton entity, always addressed at id {@code "default"} — one server-wide record. */
@Component(id = "content-settings")
public class ContentSettingsEntity extends KeyValueEntity<ContentSettings> {

  public static final String ID = "default";

  @Override
  public ContentSettings emptyState() {
    return ContentSettings.defaults();
  }

  public Effect<ContentSettings> set(ContentSettings command) {
    return effects().updateState(command).thenReply(command);
  }

  public ReadOnlyEffect<ContentSettings> get() {
    return effects().reply(currentState());
  }
}
