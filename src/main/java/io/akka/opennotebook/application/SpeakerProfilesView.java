package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.util.List;

/** Every speaker profile — the source's {@code GET /speaker-profiles} lists all of them. */
@Component(id = "speaker-profiles-view")
public class SpeakerProfilesView extends View {

  public record Entry(
      String id, String name, String description, String voiceModelId, List<String> speakerNames, long createdAtMillis) {}

  public record Entries(List<Entry> items) {}

  @Consume.FromKeyValueEntity(SpeakerProfileEntity.class)
  public static class SpeakerProfiles extends TableUpdater<Entry> {
    public Effect<Entry> onUpdate(io.akka.opennotebook.domain.SpeakerProfile state) {
      if (!state.exists()) return effects().deleteRow();
      return effects()
          .updateRow(
              new Entry(
                  state.id(),
                  state.name(),
                  state.description(),
                  state.voiceModelId(),
                  state.speakerNames(),
                  state.createdAt() == null ? 0 : state.createdAt().toEpochMilli()));
    }
  }

  @Query("SELECT * AS items FROM speaker_profiles")
  public QueryEffect<Entries> all() {
    return queryResult();
  }
}
