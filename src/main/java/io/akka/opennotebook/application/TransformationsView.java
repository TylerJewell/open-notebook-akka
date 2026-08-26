package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.opennotebook.domain.Transformation;
import java.util.List;

@Component(id = "transformations-view")
public class TransformationsView extends View {

  public record Entry(String id, String name, String title, String description, int applyDefault) {}

  public record Entries(List<Entry> items) {}

  @Consume.FromKeyValueEntity(TransformationEntity.class)
  public static class Transformations extends TableUpdater<Entry> {
    public Effect<Entry> onUpdate(Transformation state) {
      if (!state.exists()) return effects().deleteRow();
      return effects()
          .updateRow(
              new Entry(state.id(), state.name(), state.title(), state.description(), state.applyDefault() ? 1 : 0));
    }
  }

  @Query("SELECT * AS items FROM transformations ORDER BY name")
  public QueryEffect<Entries> all() {
    return queryResult();
  }
}
