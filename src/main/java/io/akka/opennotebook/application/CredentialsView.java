package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.util.List;

/** Every credential — the source's {@code GET /credentials} lists all provider accounts. */
@Component(id = "credentials-view")
public class CredentialsView extends View {

  public record Entry(String id, String name, String provider, String modalities, long createdAtMillis) {}

  public record Entries(List<Entry> items) {}

  @Consume.FromKeyValueEntity(CredentialEntity.class)
  public static class Credentials extends TableUpdater<Entry> {
    public Effect<Entry> onUpdate(io.akka.opennotebook.domain.Credential state) {
      if (!state.exists()) return effects().deleteRow();
      return effects()
          .updateRow(
              new Entry(
                  state.id(),
                  state.name(),
                  state.provider(),
                  String.join(",", state.modalities()),
                  state.createdAt() == null ? 0 : state.createdAt().toEpochMilli()));
    }
  }

  // No ORDER BY: a WHERE-less query cannot sort on the real runtime ("ORDER BY columns must
  // also be indexed (part of the WHERE conditions)") -- see SourcesView's class doc, found while
  // running this port as a real service rather than only through TestKit-backed tests.
  @Query("SELECT * AS items FROM credentials")
  public QueryEffect<Entries> all() {
    return queryResult();
  }
}
