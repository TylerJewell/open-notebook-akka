package io.akka.opennotebook.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.opennotebook.domain.DeletePreview;
import io.akka.opennotebook.domain.NoteType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 8, 9, 10, 11 — cascading a notebook delete across sources and notes. */
class NotebookDeletionWorkflowIntegrationTest extends TestKitSupport {

  private String newNotebook() {
    String id = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(id)
        .method(NotebookEntity::create)
        .invoke(new NotebookEntity.Create("NB", "d", Instant.now()));
    return id;
  }

  private String newSource(List<String> notebookIds) {
    String id = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(id)
        .method(SourceEntity::createPlaceholder)
        .invoke(new SourceEntity.CreatePlaceholder("s", null, null, notebookIds, Instant.now()));
    for (String nb : notebookIds) {
      componentClient
          .forEventSourcedEntity(nb)
          .method(NotebookEntity::linkSource)
          .invoke(new NotebookEntity.SourceLinked(id, Instant.now()));
    }
    return id;
  }

  private String newNote(String notebookId) {
    String id = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(id)
        .method(NoteEntity::create)
        .invoke(new NoteEntity.Create("n", "content", NoteType.HUMAN, List.of(notebookId), Instant.now()));
    componentClient
        .forEventSourcedEntity(notebookId)
        .method(NotebookEntity::linkNote)
        .invoke(new NotebookEntity.NoteLinked(id, Instant.now()));
    return id;
  }

  @Test
  void exclusiveSourceDeletedSharedSourceUnlinkedNotesAlwaysDeleted() {
    String nb1 = newNotebook();
    String nb2 = newNotebook();
    String exclusiveSource = newSource(List.of(nb1));
    String sharedSource = newSource(List.of(nb1, nb2));
    String note = newNote(nb1);

    String workflowId = "delete-" + nb1;
    componentClient
        .forWorkflow(workflowId)
        .method(NotebookDeletionWorkflow::start)
        .invoke(new NotebookDeletionWorkflow.Start(nb1, true));

    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var result =
                  componentClient
                      .forWorkflow(workflowId)
                      .method(NotebookDeletionWorkflow::result)
                      .invoke();
              assertThat(result.deletedNotes()).isEqualTo(1);
              assertThat(result.deletedSources()).isEqualTo(1);
              assertThat(result.unlinkedSources()).isEqualTo(1);
            });

    // R12: the exclusive source is gone outright, not merely marked — get() 404s the same way
    // it would for a source that never existed.
    assertThatThrownBy(
            () ->
                componentClient
                    .forEventSourcedEntity(exclusiveSource)
                    .method(SourceEntity::get)
                    .invoke())
        .isInstanceOf(Exception.class);

    var sharedAfter =
        componentClient.forEventSourcedEntity(sharedSource).method(SourceEntity::get).invoke();
    assertThat(sharedAfter.exists()).isTrue();
    assertThat(sharedAfter.notebookIds()).containsExactly(nb2);

    assertThatThrownBy(
            () -> componentClient.forEventSourcedEntity(note).method(NoteEntity::get).invoke())
        .isInstanceOf(Exception.class);

    assertThatThrownBy(
            () -> componentClient.forEventSourcedEntity(nb1).method(NotebookEntity::get).invoke())
        .isInstanceOf(Exception.class);
  }

  @Test
  void previewCountsMatchSubsequentDelete() {
    String nb1 = newNotebook();
    String nb2 = newNotebook();
    newSource(List.of(nb1)); // exclusive
    newSource(List.of(nb1, nb2)); // shared
    newNote(nb1);

    var notebook = componentClient.forEventSourcedEntity(nb1).method(NotebookEntity::get).invoke();
    int exclusive = 0;
    int shared = 0;
    for (String sourceId : notebook.sourceIds()) {
      var source = componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
      if (source.isExclusiveTo(nb1)) {
        exclusive++;
      } else {
        shared++;
      }
    }
    var preview = new DeletePreview(notebook.noteIds().size(), exclusive, shared);
    assertThat(preview).isEqualTo(new DeletePreview(1, 1, 1));

    String workflowId = "delete-preview-check-" + nb1;
    componentClient
        .forWorkflow(workflowId)
        .method(NotebookDeletionWorkflow::start)
        .invoke(new NotebookDeletionWorkflow.Start(nb1, true));

    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var result =
                  componentClient
                      .forWorkflow(workflowId)
                      .method(NotebookDeletionWorkflow::result)
                      .invoke();
              assertThat(result.deletedNotes()).isEqualTo(preview.noteCount());
              assertThat(result.deletedSources()).isEqualTo(preview.exclusiveSourceCount());
              assertThat(result.unlinkedSources()).isEqualTo(preview.sharedSourceCount());
            });
  }

  @Test
  void deleteExclusiveSourcesFalseUnlinksEveryoneAndDeletesNoSource() {
    String nb1 = newNotebook();
    String source = newSource(List.of(nb1));

    String workflowId = "delete-nofalse-" + nb1;
    componentClient
        .forWorkflow(workflowId)
        .method(NotebookDeletionWorkflow::start)
        .invoke(new NotebookDeletionWorkflow.Start(nb1, false));

    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var result =
                  componentClient
                      .forWorkflow(workflowId)
                      .method(NotebookDeletionWorkflow::result)
                      .invoke();
              assertThat(result.deletedSources()).isEqualTo(0);
              assertThat(result.unlinkedSources()).isEqualTo(1);
            });

    var sourceAfter = componentClient.forEventSourcedEntity(source).method(SourceEntity::get).invoke();
    assertThat(sourceAfter.exists()).isTrue();
    assertThat(sourceAfter.notebookIds()).isEmpty();
  }

  @Test
  void deletingANotebookDeletesEveryLinkedChatSession() {
    String nb1 = newNotebook();
    String chatId = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(chatId)
        .method(ChatSessionEntity::create)
        .invoke(new ChatSessionEntity.Create(nb1, Instant.now()));
    componentClient
        .forEventSourcedEntity(nb1)
        .method(NotebookEntity::linkChatSession)
        .invoke(new NotebookEntity.ChatSessionLinked(chatId, Instant.now()));

    String workflowId = "delete-chat-" + nb1;
    componentClient
        .forWorkflow(workflowId)
        .method(NotebookDeletionWorkflow::start)
        .invoke(new NotebookDeletionWorkflow.Start(nb1, true));

    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var result =
                  componentClient.forWorkflow(workflowId).method(NotebookDeletionWorkflow::result).invoke();
              assertThat(result.deletedChatSessions()).isEqualTo(1);
            });

    assertThatThrownBy(
            () -> componentClient.forEventSourcedEntity(chatId).method(ChatSessionEntity::get).invoke())
        .isInstanceOf(Exception.class);
  }
}
