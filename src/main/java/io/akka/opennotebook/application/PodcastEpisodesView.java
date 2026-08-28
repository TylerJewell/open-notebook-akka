package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.opennotebook.domain.PodcastStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Every podcast episode — the source's {@code GET /podcasts/episodes} lists every one that has
 * ever been generated, not only the most recently started (the narrowing this replaces). */
@Component(id = "podcast-episodes-view")
public class PodcastEpisodesView extends View {

  public record Entry(
      String id,
      String notebookId,
      String episodeProfileId,
      String name,
      String status,
      String briefing,
      Optional<String> outline,
      Optional<String> transcript,
      Optional<String> audioBase64,
      Optional<String> errorMessage,
      Instant createdAt) {}

  public record Entries(List<Entry> items) {}

  @Consume.FromEventSourcedEntity(PodcastEpisodeEntity.class)
  public static class Episodes extends TableUpdater<Entry> {
    public Effect<Entry> onEvent(PodcastEpisodeEntity.Event event) {
      return switch (event) {
        case PodcastEpisodeEntity.EpisodeCreated e ->
            effects()
                .updateRow(
                    new Entry(
                        e.episodeId(), e.notebookId(), e.episodeProfileId(), e.name(),
                        PodcastStatus.PENDING.name(), e.briefing(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), e.at()));
        case PodcastEpisodeEntity.StatusChanged e ->
            effects().updateRow(withStatus(e.status().name()));
        case PodcastEpisodeEntity.OutlineSet e ->
            effects()
                .updateRow(
                    new Entry(
                        rowState().id(), rowState().notebookId(), rowState().episodeProfileId(), rowState().name(),
                        rowState().status(), rowState().briefing(), Optional.ofNullable(e.outline()),
                        rowState().transcript(), rowState().audioBase64(), rowState().errorMessage(), rowState().createdAt()));
        case PodcastEpisodeEntity.TranscriptSet e ->
            effects()
                .updateRow(
                    new Entry(
                        rowState().id(), rowState().notebookId(), rowState().episodeProfileId(), rowState().name(),
                        rowState().status(), rowState().briefing(), rowState().outline(),
                        Optional.ofNullable(e.transcript()), rowState().audioBase64(), rowState().errorMessage(), rowState().createdAt()));
        case PodcastEpisodeEntity.Completed e ->
            effects()
                .updateRow(
                    new Entry(
                        rowState().id(), rowState().notebookId(), rowState().episodeProfileId(), rowState().name(),
                        PodcastStatus.COMPLETED.name(), rowState().briefing(), rowState().outline(), rowState().transcript(),
                        Optional.ofNullable(e.audioBase64()), Optional.empty(), rowState().createdAt()));
        case PodcastEpisodeEntity.Failed e ->
            effects()
                .updateRow(
                    new Entry(
                        rowState().id(), rowState().notebookId(), rowState().episodeProfileId(), rowState().name(),
                        PodcastStatus.FAILED.name(), rowState().briefing(), rowState().outline(), rowState().transcript(),
                        rowState().audioBase64(), Optional.ofNullable(e.errorMessage()), rowState().createdAt()));
        case PodcastEpisodeEntity.Deleted e -> effects().deleteRow();
      };
    }

    private Entry withStatus(String status) {
      return new Entry(
          rowState().id(), rowState().notebookId(), rowState().episodeProfileId(), rowState().name(),
          status, rowState().briefing(), rowState().outline(), rowState().transcript(),
          rowState().audioBase64(), rowState().errorMessage(), rowState().createdAt());
    }
  }

  @Query("SELECT * AS items FROM podcast_episodes")
  public QueryEffect<Entries> all() {
    return queryResult();
  }
}
