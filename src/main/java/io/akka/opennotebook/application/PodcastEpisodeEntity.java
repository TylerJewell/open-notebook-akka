package io.akka.opennotebook.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.opennotebook.domain.PodcastEpisode;
import io.akka.opennotebook.domain.PodcastStatus;
import java.time.Instant;

/** A podcast episode's generation lifecycle (SPEC-001 §Podcasts) — outline, transcript, audio. */
@Component(id = "podcast-episode")
public class PodcastEpisodeEntity extends EventSourcedEntity<PodcastEpisode, PodcastEpisodeEntity.Event> {

  public sealed interface Event {}

  @TypeName("episode-created")
  public record EpisodeCreated(
      String episodeId, String notebookId, String episodeProfileId, String name, String briefing, Instant at)
      implements Event {}

  @TypeName("episode-status-changed")
  public record StatusChanged(PodcastStatus status, Instant at) implements Event {}

  @TypeName("episode-outline-set")
  public record OutlineSet(String outline, Instant at) implements Event {}

  @TypeName("episode-transcript-set")
  public record TranscriptSet(String transcript, Instant at) implements Event {}

  @TypeName("episode-completed")
  public record Completed(String audioBase64, Instant at) implements Event {}

  @TypeName("episode-failed")
  public record Failed(String errorMessage, Instant at) implements Event {}

  public record Create(String notebookId, String episodeProfileId, String name, String briefing, Instant now) {}

  @Override
  public PodcastEpisode emptyState() {
    return PodcastEpisode.empty();
  }

  public Effect<Done> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Episode already exists");
    }
    var event =
        new EpisodeCreated(
            commandContext().entityId(), command.notebookId(), command.episodeProfileId(), command.name(),
            command.briefing(), command.now());
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> setStatus(StatusChanged command) {
    if (!currentState().exists()) return effects().error("Episode not found");
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> setOutline(OutlineSet command) {
    if (!currentState().exists()) return effects().error("Episode not found");
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> setTranscript(TranscriptSet command) {
    if (!currentState().exists()) return effects().error("Episode not found");
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> complete(Completed command) {
    if (!currentState().exists()) return effects().error("Episode not found");
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> fail(Failed command) {
    if (!currentState().exists()) return effects().error("Episode not found");
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public ReadOnlyEffect<PodcastEpisode> get() {
    if (!currentState().exists()) return effects().error("Episode not found");
    return effects().reply(currentState());
  }

  @Override
  public PodcastEpisode applyEvent(Event event) {
    return switch (event) {
      case EpisodeCreated e ->
          PodcastEpisode.create(e.episodeId(), e.notebookId(), e.episodeProfileId(), e.name(), e.briefing(), e.at());
      case StatusChanged e -> currentState().withStatus(e.status());
      case OutlineSet e -> currentState().withOutline(e.outline());
      case TranscriptSet e -> currentState().withTranscript(e.transcript());
      case Completed e -> currentState().withCompleted(e.audioBase64());
      case Failed e -> currentState().withFailed(e.errorMessage());
    };
  }
}
