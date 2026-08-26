package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;
import io.akka.opennotebook.domain.SpeakerProfile;
import java.time.Instant;
import java.util.List;

@Component(id = "speaker-profile")
public class SpeakerProfileEntity extends KeyValueEntity<SpeakerProfile> {

  public record Create(String name, String description, String voiceModelId, List<String> speakerNames, Instant now) {}

  private final String id;

  public SpeakerProfileEntity(KeyValueEntityContext context) {
    this.id = context.entityId();
  }

  @Override
  public SpeakerProfile emptyState() {
    return SpeakerProfile.empty();
  }

  public Effect<SpeakerProfile> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Speaker profile already exists");
    }
    if (command.name() == null || command.name().isBlank()) {
      return effects().error("Speaker profile name cannot be empty");
    }
    SpeakerProfile created =
        SpeakerProfile.create(
            id, command.name(), command.description(), command.voiceModelId(),
            command.speakerNames() == null ? List.of() : command.speakerNames(), command.now());
    return effects().updateState(created).thenReply(created);
  }

  public Effect<String> delete() {
    if (!currentState().exists()) {
      return effects().error("Speaker profile not found");
    }
    return effects().deleteEntity().thenReply("deleted");
  }

  public ReadOnlyEffect<SpeakerProfile> get() {
    if (!currentState().exists()) {
      return effects().error("Speaker profile not found");
    }
    return effects().reply(currentState());
  }
}
