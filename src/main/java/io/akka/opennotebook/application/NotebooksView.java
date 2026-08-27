package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every notebook — the frontend's dashboard list (`GET /api/notebooks`), which has no equivalent
 * in the bare-path endpoint surface: {@code NotebookEndpoint} only ever addresses one notebook at
 * a time by id, since the original slice never needed to list them all.
 *
 * <p>{@code description} is {@code Optional<String>} rather than plain {@code String} -- see
 * {@link SourcesView}'s class doc for why a nullable View row field needs the wrapper.
 */
@Component(id = "notebooks-view")
public class NotebooksView extends View {

  public record Entry(
      String notebookId,
      String name,
      Optional<String> description,
      boolean archived,
      int sourceCount,
      int noteCount,
      Instant createdAt,
      Instant updatedAt) {

    Entry withSourceCount(int delta) {
      return new Entry(notebookId, name, description, archived, sourceCount + delta, noteCount, createdAt, updatedAt);
    }

    Entry withNoteCount(int delta) {
      return new Entry(notebookId, name, description, archived, sourceCount, noteCount + delta, createdAt, updatedAt);
    }
  }

  public record Entries(List<Entry> items) {}

  @Consume.FromEventSourcedEntity(NotebookEntity.class)
  public static class Notebooks extends TableUpdater<Entry> {
    public Effect<Entry> onEvent(NotebookEntity.Event event) {
      return switch (event) {
        case NotebookEntity.NotebookCreated e ->
            effects()
                .updateRow(
                    new Entry(
                        e.notebookId(), e.name(), Optional.ofNullable(e.description()), false, 0, 0,
                        e.createdAt(), e.createdAt()));
        case NotebookEntity.SourceLinked e -> effects().updateRow(rowState().withSourceCount(1));
        case NotebookEntity.SourceUnlinked e -> effects().updateRow(rowState().withSourceCount(-1));
        case NotebookEntity.NoteLinked e -> effects().updateRow(rowState().withNoteCount(1));
        case NotebookEntity.ChatSessionLinked e -> effects().ignore();
        case NotebookEntity.NotebookUpdated e ->
            effects()
                .updateRow(
                    new Entry(
                        rowState().notebookId(), e.name(), Optional.ofNullable(e.description()), e.archived(),
                        rowState().sourceCount(), rowState().noteCount(), rowState().createdAt(), e.at()));
        case NotebookEntity.NotebookDeleted e -> effects().deleteRow();
      };
    }
  }

  // Real-runtime constraint (see all()'s comment): an ORDER BY column must be the same column
  // the WHERE clause filters on. archived != updatedAt, so no ORDER BY here either -- callers
  // sort client-side.
  @Query("SELECT * AS items FROM notebooks WHERE archived = false")
  public QueryEffect<Entries> active() {
    return queryResult();
  }

  // No WHERE clause to drive an index scan, so no ORDER BY either -- the real runtime rejects
  // "sort with no filter" outright (AK-00111-adjacent validation: "Results cannot be sorted with
  // ORDER BY when using inverted indexes"), which the embedded TestKit does not enforce, so this
  // surfaced only against a real running service, not `mvn verify`. Callers sort client-side.
  @Query("SELECT * AS items FROM notebooks")
  public QueryEffect<Entries> all() {
    return queryResult();
  }

  @Query("SELECT * AS items FROM notebooks WHERE archived = :archived")
  public QueryEffect<Entries> byArchived(boolean archived) {
    return queryResult();
  }
}
