package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.util.List;

/** Every episode profile — the source's {@code GET /episode-profiles} lists all of them. */
@Component(id = "episode-profiles-view")
public class EpisodeProfilesView extends View {

  public record Entry(
      String id,
      String name,
      String description,
      String outlineModelId,
      String transcriptModelId,
      String speakerProfileId,
      String defaultBriefing,
      int numSegments,
      long createdAtMillis) {}

  public record Entries(List<Entry> items) {}

  @Consume.FromKeyValueEntity(EpisodeProfileEntity.class)
  public static class EpisodeProfiles extends TableUpdater<Entry> {
    public Effect<Entry> onUpdate(io.akka.opennotebook.domain.EpisodeProfile state) {
      if (!state.exists()) return effects().deleteRow();
      return effects()
          .updateRow(
              new Entry(
                  state.id(),
                  state.name(),
                  state.description(),
                  state.outlineModelId(),
                  state.transcriptModelId(),
                  state.speakerProfileId(),
                  state.defaultBriefing(),
                  state.numSegments(),
                  state.createdAt() == null ? 0 : state.createdAt().toEpochMilli()));
    }
  }

  @Query("SELECT * AS items FROM episode_profiles")
  public QueryEffect<Entries> all() {
    return queryResult();
  }
}
