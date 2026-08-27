package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Every note, queryable by the notebook it is linked to — the frontend's {@code GET
 * /api/notes?notebook_id=} list, the same gap {@link SourcesView} closes for sources.
 *
 * <p>{@code title}/{@code content} are {@code Optional<String>} -- see {@link SourcesView}'s
 * class doc for why a nullable View row field needs the wrapper. */
@Component(id = "notes-view")
public class NotesView extends View {

  public record Entry(
      String noteId,
      Optional<String> title,
      Optional<String> content,
      String noteType,
      List<String> notebookIds,
      Instant createdAt,
      Instant updatedAt) {}

  public record Entries(List<Entry> items) {}

  @Consume.FromEventSourcedEntity(NoteEntity.class)
  public static class Notes extends TableUpdater<Entry> {
    public Effect<Entry> onEvent(NoteEntity.Event event) {
      return switch (event) {
        case NoteEntity.NoteCreated e ->
            effects()
                .updateRow(
                    new Entry(
                        e.noteId(), Optional.ofNullable(e.title()), Optional.ofNullable(e.content()),
                        e.noteType().name(), List.copyOf(e.notebookIds()), e.at(), e.at()));
        case NoteEntity.NotebookLinked e -> {
          var linked = new ArrayList<>(rowState().notebookIds());
          if (!linked.contains(e.notebookId())) linked.add(e.notebookId());
          yield effects()
              .updateRow(
                  new Entry(
                      rowState().noteId(), rowState().title(), rowState().content(), rowState().noteType(),
                      List.copyOf(linked), rowState().createdAt(), e.at()));
        }
        case NoteEntity.NoteUpdated e ->
            effects()
                .updateRow(
                    new Entry(
                        rowState().noteId(), Optional.ofNullable(e.title()), Optional.ofNullable(e.content()),
                        rowState().noteType(), rowState().notebookIds(), rowState().createdAt(), e.at()));
        case NoteEntity.NoteDeleted e -> effects().deleteRow();
      };
    }
  }

  // No ORDER BY -- see SourcesView.byNotebook()'s comment; ApiNoteEndpoint sorts client-side.
  @Query("SELECT * AS items FROM notes WHERE :notebookId = ANY(notebookIds)")
  public QueryEffect<Entries> byNotebook(String notebookId) {
    return queryResult();
  }

  // See NotebooksView.all()'s comment: no WHERE, so no ORDER BY -- the real runtime rejects it.
  @Query("SELECT * AS items FROM notes")
  public QueryEffect<Entries> all() {
    return queryResult();
  }
}
