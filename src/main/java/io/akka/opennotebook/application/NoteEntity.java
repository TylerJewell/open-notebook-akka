package io.akka.opennotebook.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.opennotebook.domain.Note;
import io.akka.opennotebook.domain.NoteType;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** A note's own lifecycle (R13–R15) — see SPEC-001. */
@Component(id = "note")
public class NoteEntity extends EventSourcedEntity<Note, NoteEntity.Event> {

  public sealed interface Event {}

  @TypeName("note-created")
  public record NoteCreated(
      String noteId,
      String title,
      String content,
      NoteType noteType,
      Set<String> notebookIds,
      Instant at)
      implements Event {}

  @TypeName("note-notebook-linked")
  public record NotebookLinked(String notebookId, Instant at) implements Event {}

  @TypeName("note-deleted")
  public record NoteDeleted(Instant at) implements Event {}

  public record Create(
      String title, String content, NoteType noteType, List<String> notebookIds, Instant now) {}

  @Override
  public Note emptyState() {
    return new Note(null, null, null, null, Set.of(), null, null, false);
  }

  public Effect<Done> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Note already exists");
    }
    if (command.content() != null && command.content().isBlank()) {
      return effects().error("Note content cannot be empty");
    }
    var event =
        new NoteCreated(
            commandContext().entityId(),
            command.title(),
            command.content(),
            command.noteType(),
            Set.copyOf(command.notebookIds()),
            command.now());
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> addToNotebook(NotebookLinked command) {
    if (!currentState().exists()) {
      return effects().error("Note not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> delete(NoteDeleted command) {
    if (!currentState().exists()) {
      return effects().error("Note not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public ReadOnlyEffect<Note> get() {
    if (!currentState().exists()) {
      return effects().error("Note not found");
    }
    return effects().reply(currentState());
  }

  @Override
  public Note applyEvent(Event event) {
    return switch (event) {
      case NoteCreated e ->
          Note.create(e.noteId(), e.title(), e.content(), e.noteType(), e.notebookIds(), e.at());
      case NotebookLinked e -> currentState().withNotebookLinked(e.notebookId(), e.at());
      case NoteDeleted e -> currentState().withDeleted(e.at());
    };
  }
}
