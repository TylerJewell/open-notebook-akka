package io.akka.opennotebook.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NoteTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:05Z");

  @Test
  void emptyContentRejectedNonEmptyContentAlwaysSavable() {
    assertThatThrownBy(
            () -> Note.create("n1", "t", "   ", NoteType.HUMAN, Set.of("nb1"), T0))
        .isInstanceOf(IllegalArgumentException.class);

    var note = Note.create("n1", "t", "hello world", NoteType.HUMAN, Set.of("nb1"), T0);
    assertThat(note.content()).isEqualTo("hello world");
    assertThat(note.exists()).isTrue();
  }

  @Test
  void nullContentIsAllowed() {
    var note = Note.create("n1", "t", null, NoteType.HUMAN, Set.of("nb1"), T0);
    assertThat(note.content()).isNull();
  }

  @Test
  void linksToMultipleNotebooks() {
    var note = Note.create("n1", "t", "c", NoteType.HUMAN, Set.of("nb1"), T0);
    var linked = note.withNotebookLinked("nb2", T1);
    assertThat(linked.notebookIds()).containsExactlyInAnyOrder("nb1", "nb2");
  }
}
