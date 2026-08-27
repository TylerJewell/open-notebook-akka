package io.akka.opennotebook.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.opennotebook.domain.Notebook;
import java.time.Instant;
import java.util.Set;

/**
 * A notebook's own state: its identity, and which sources and notes it currently holds.
 *
 * <p>Cascading a delete across sources and notes is not this entity's job — an Event Sourced
 * Entity command handler is for one entity's own state transition, and the cascade needs to
 * call other entities, which only a Workflow or an Endpoint may do. {@link
 * NotebookDeletionWorkflow} does the cascade and calls {@link #delete} last.
 */
@Component(id = "notebook")
public class NotebookEntity extends EventSourcedEntity<Notebook, NotebookEntity.Event> {

  public sealed interface Event {}

  @TypeName("notebook-created")
  public record NotebookCreated(
      String notebookId, String name, String description, Instant createdAt) implements Event {}

  @TypeName("source-linked")
  public record SourceLinked(String sourceId, Instant at) implements Event {}

  @TypeName("source-unlinked")
  public record SourceUnlinked(String sourceId, Instant at) implements Event {}

  @TypeName("note-linked")
  public record NoteLinked(String noteId, Instant at) implements Event {}

  @TypeName("chat-session-linked")
  public record ChatSessionLinked(String chatId, Instant at) implements Event {}

  @TypeName("notebook-updated")
  public record NotebookUpdated(String name, String description, boolean archived, Instant at)
      implements Event {}

  @TypeName("notebook-deleted")
  public record NotebookDeleted(Instant at) implements Event {}

  public record Create(String name, String description, Instant now) {}

  public record Update(String name, String description, Boolean archived, Instant now) {}

  @Override
  public Notebook emptyState() {
    return new Notebook(null, null, null, false, Set.of(), Set.of(), Set.of(), null, null, false);
  }

  public Effect<Done> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Notebook already exists");
    }
    if (command.name() == null || command.name().isBlank()) {
      return effects().error("Notebook name cannot be empty");
    }
    var event =
        new NotebookCreated(commandContext().entityId(), command.name(), command.description(), command.now());
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> linkSource(SourceLinked command) {
    if (!currentState().exists()) {
      return effects().error("Notebook not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> unlinkSource(SourceUnlinked command) {
    if (!currentState().exists()) {
      return effects().error("Notebook not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> update(Update command) {
    if (!currentState().exists()) {
      return effects().error("Notebook not found");
    }
    String name = command.name() == null ? currentState().name() : command.name();
    if (name == null || name.isBlank()) {
      return effects().error("Notebook name cannot be empty");
    }
    String description = command.description() == null ? currentState().description() : command.description();
    boolean archived = command.archived() == null ? currentState().archived() : command.archived();
    var event = new NotebookUpdated(name, description, archived, command.now());
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> linkNote(NoteLinked command) {
    if (!currentState().exists()) {
      return effects().error("Notebook not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> linkChatSession(ChatSessionLinked command) {
    if (!currentState().exists()) {
      return effects().error("Notebook not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> delete(NotebookDeleted command) {
    if (!currentState().exists()) {
      return effects().error("Notebook not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public ReadOnlyEffect<Notebook> get() {
    if (!currentState().exists()) {
      return effects().error("Notebook not found");
    }
    return effects().reply(currentState());
  }

  @Override
  public Notebook applyEvent(Event event) {
    return switch (event) {
      case NotebookCreated e ->
          Notebook.create(e.notebookId(), e.name(), e.description(), e.createdAt());
      case SourceLinked e -> currentState().withSourceLinked(e.sourceId(), e.at());
      case SourceUnlinked e -> currentState().withSourceUnlinked(e.sourceId(), e.at());
      case NoteLinked e -> currentState().withNoteLinked(e.noteId(), e.at());
      case ChatSessionLinked e -> currentState().withChatSessionLinked(e.chatId(), e.at());
      case NotebookUpdated e -> currentState().withUpdated(e.name(), e.description(), e.archived(), e.at());
      case NotebookDeleted e -> currentState().withDeleted(e.at());
    };
  }
}
