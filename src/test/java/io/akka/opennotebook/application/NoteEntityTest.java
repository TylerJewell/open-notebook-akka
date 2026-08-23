package io.akka.opennotebook.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.opennotebook.domain.Note;
import io.akka.opennotebook.domain.NoteType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 13–15 — a note's own lifecycle. */
class NoteEntityTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:05Z");

  private static EventSourcedTestKit<Note, NoteEntity.Event, NoteEntity> kit(String id) {
    return EventSourcedTestKit.of(id, NoteEntity::new);
  }

  @Test
  void emptyContentRejectedNonEmptyContentAlwaysSavable() {
    var rejected = kit("n1");
    var result =
        rejected
            .method(NoteEntity::create)
            .invoke(new NoteEntity.Create("t", "   ", NoteType.HUMAN, List.of("nb1"), T0));
    assertThat(result.isError()).isTrue();

    var accepted = kit("n2");
    accepted
        .method(NoteEntity::create)
        .invoke(new NoteEntity.Create("t", "hello world", NoteType.HUMAN, List.of("nb1"), T0));
    assertThat(accepted.getState().content()).isEqualTo("hello world");
    assertThat(accepted.getState().exists()).isTrue();
  }

  @Test
  void linksToMultipleNotebooks() {
    var testKit = kit("n1");
    testKit
        .method(NoteEntity::create)
        .invoke(new NoteEntity.Create("t", "c", NoteType.HUMAN, List.of("nb1"), T0));
    testKit.method(NoteEntity::addToNotebook).invoke(new NoteEntity.NotebookLinked("nb2", T1));
    assertThat(testKit.getState().notebookIds()).containsExactlyInAnyOrder("nb1", "nb2");
  }

  @Test
  void deletingRemovesTheNoteEvenWhenLinkedToMultipleNotebooks() {
    var testKit = kit("n1");
    testKit
        .method(NoteEntity::create)
        .invoke(new NoteEntity.Create("t", "c", NoteType.HUMAN, List.of("nb1", "nb2"), T0));
    testKit.method(NoteEntity::delete).invoke(new NoteEntity.NoteDeleted(T1));
    assertThat(testKit.getState().deleted()).isTrue();
    assertThat(testKit.getState().exists()).isFalse();
  }
}
