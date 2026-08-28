package io.akka.opennotebook.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SourceInsightTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void saveAsNoteProducesAiNoteWithComposedTitle() {
    var insight = new Insight("i1", "Summary", "The quick brown fox.");
    var note = Note.fromInsight("n1", "fox source", insight, "nb1", T0);

    assertThat(note.noteType()).isEqualTo(NoteType.AI);
    assertThat(note.title()).isEqualTo("Summary from source fox source");
    assertThat(note.content()).isEqualTo("The quick brown fox.");
    assertThat(note.notebookIds()).containsExactly("nb1");
  }
}
