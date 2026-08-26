package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;
import io.akka.opennotebook.domain.EpisodeProfile;
import java.time.Instant;

@Component(id = "episode-profile")
public class EpisodeProfileEntity extends KeyValueEntity<EpisodeProfile> {

  public record Create(
      String name,
      String description,
      String outlineModelId,
      String transcriptModelId,
      String speakerProfileId,
      String defaultBriefing,
      int numSegments,
      Instant now) {}

  private final String id;

  public EpisodeProfileEntity(KeyValueEntityContext context) {
    this.id = context.entityId();
  }

  @Override
  public EpisodeProfile emptyState() {
    return EpisodeProfile.empty();
  }

  public Effect<EpisodeProfile> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Episode profile already exists");
    }
    if (command.name() == null || command.name().isBlank()) {
      return effects().error("Episode profile name cannot be empty");
    }
    EpisodeProfile created =
        EpisodeProfile.create(
            id, command.name(), command.description(), command.outlineModelId(), command.transcriptModelId(),
            command.speakerProfileId(), command.defaultBriefing(),
            command.numSegments() > 0 ? command.numSegments() : 5, command.now());
    return effects().updateState(created).thenReply(created);
  }

  public Effect<String> delete() {
    if (!currentState().exists()) {
      return effects().error("Episode profile not found");
    }
    return effects().deleteEntity().thenReply("deleted");
  }

  public ReadOnlyEffect<EpisodeProfile> get() {
    if (!currentState().exists()) {
      return effects().error("Episode profile not found");
    }
    return effects().reply(currentState());
  }
}
