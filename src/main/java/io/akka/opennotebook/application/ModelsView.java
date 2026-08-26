package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.opennotebook.domain.ModelRecord;
import java.util.List;

/** Every provisioned model — the source's {@code GET /models}, and per-type filters for pickers. */
@Component(id = "models-view")
public class ModelsView extends View {

  public record Entry(String id, String name, String provider, String type, String credentialId) {}

  public record Entries(List<Entry> items) {}

  @Consume.FromKeyValueEntity(ModelEntity.class)
  public static class Models extends TableUpdater<Entry> {
    public Effect<Entry> onUpdate(ModelRecord state) {
      if (!state.exists()) return effects().deleteRow();
      return effects()
          .updateRow(
              new Entry(state.id(), state.name(), state.provider(), state.type(), state.credentialId()));
    }
  }

  @Query("SELECT * AS items FROM models ORDER BY name")
  public QueryEffect<Entries> all() {
    return queryResult();
  }

  @Query("SELECT * AS items FROM models WHERE type = :type ORDER BY name")
  public QueryEffect<Entries> byType(String type) {
    return queryResult();
  }
}
