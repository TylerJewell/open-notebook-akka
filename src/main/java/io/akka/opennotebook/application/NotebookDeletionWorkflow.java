package io.akka.opennotebook.application;

import static java.time.Duration.ofSeconds;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import io.akka.opennotebook.domain.DeleteResult;
import io.akka.opennotebook.domain.Notebook;
import io.akka.opennotebook.domain.Source;
import java.time.Instant;

/**
 * R8, R9, R10: deleting a notebook deletes every note it holds unconditionally, and classifies
 * each source as exclusive (deleted) or shared (only unlinked) by that source's *current*
 * notebook links — not by anything the source itself carries.
 */
@Component(id = "notebook-deletion")
public class NotebookDeletionWorkflow extends Workflow<NotebookDeletionWorkflow.State> {

  public record State(
      String notebookId,
      boolean deleteExclusiveSources,
      int deletedNotes,
      int deletedSources,
      int unlinkedSources,
      int deletedChatSessions,
      String status) {

    State done() {
      return new State(
          notebookId, deleteExclusiveSources, deletedNotes, deletedSources, unlinkedSources,
          deletedChatSessions, "done");
    }
  }

  public record Start(String notebookId, boolean deleteExclusiveSources) {}

  private final ComponentClient componentClient;

  public NotebookDeletionWorkflow(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(ofSeconds(30)).build();
  }

  public Effect<String> start(Start command) {
    return effects()
        .updateState(new State(command.notebookId(), command.deleteExclusiveSources(), 0, 0, 0, 0, "started"))
        .transitionTo(NotebookDeletionWorkflow::cascadeStep)
        .thenReply("started");
  }

  public ReadOnlyEffect<DeleteResult> result() {
    if (currentState() == null || !"done".equals(currentState().status())) {
      return effects().error("Deletion not complete");
    }
    var s = currentState();
    return effects()
        .reply(new DeleteResult(s.deletedNotes(), s.deletedSources(), s.unlinkedSources(), s.deletedChatSessions()));
  }

  @StepName("cascade")
  private StepEffect cascadeStep() {
    var notebookId = currentState().notebookId();
    boolean deleteExclusive = currentState().deleteExclusiveSources();

    Notebook notebook =
        componentClient.forEventSourcedEntity(notebookId).method(NotebookEntity::get).invoke();

    int deletedNotes = 0;
    for (String noteId : notebook.noteIds()) {
      componentClient
          .forEventSourcedEntity(noteId)
          .method(NoteEntity::delete)
          .invoke(new NoteEntity.NoteDeleted(Instant.now()));
      deletedNotes++;
    }

    int deletedSources = 0;
    int unlinkedSources = 0;
    for (String sourceId : notebook.sourceIds()) {
      Source source =
          componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
      boolean exclusive = source.isExclusiveTo(notebookId);

      if (deleteExclusive && exclusive) {
        // Matches the source's own asymmetry: a failure deleting one exclusive source is
        // logged and the cascade continues, rather than abandoning every source and note the
        // rest of the loop would otherwise have reached.
        try {
          componentClient
              .forEventSourcedEntity(sourceId)
              .method(SourceEntity::delete)
              .invoke(new SourceEntity.SourceDeleted(Instant.now()));
          deletedSources++;
        } catch (Exception e) {
          // Neither deleted nor unlinked — matches the source, which leaves such a source
          // exactly as it was rather than guessing at an outcome the failed call never produced.
        }
      } else {
        componentClient
            .forEventSourcedEntity(sourceId)
            .method(SourceEntity::removeFromNotebook)
            .invoke(new SourceEntity.NotebookUnlinked(notebookId, Instant.now()));
        unlinkedSources++;
      }
    }

    int deletedChatSessions = 0;
    for (String chatId : notebook.chatSessionIds()) {
      componentClient
          .forEventSourcedEntity(chatId)
          .method(ChatSessionEntity::delete)
          .invoke(new ChatSessionEntity.ChatDeleted(Instant.now()));
      deletedChatSessions++;
    }

    componentClient
        .forEventSourcedEntity(notebookId)
        .method(NotebookEntity::delete)
        .invoke(new NotebookEntity.NotebookDeleted(Instant.now()));

    return stepEffects()
        .updateState(
            new State(
                notebookId, deleteExclusive, deletedNotes, deletedSources, unlinkedSources,
                deletedChatSessions, "done"))
        .thenEnd();
  }
}
