package io.akka.opennotebook.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.opennotebook.domain.Notebook;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotebookEntityTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:05Z");

  private static EventSourcedTestKit<Notebook, NotebookEntity.Event, NotebookEntity> kit(String id) {
    return EventSourcedTestKit.of(id, NotebookEntity::new);
  }

  @Test
  void createRejectsEmptyName() {
    var testKit = kit("nb1");
    var result = testKit.method(NotebookEntity::create).invoke(new NotebookEntity.Create("", "d", T0));
    assertThat(result.isError()).isTrue();
  }

  @Test
  void linkingSourcesAndNotesAccumulates() {
    var testKit = kit("nb1");
    testKit.method(NotebookEntity::create).invoke(new NotebookEntity.Create("NB", "d", T0));
    testKit.method(NotebookEntity::linkSource).invoke(new NotebookEntity.SourceLinked("s1", T1));
    testKit.method(NotebookEntity::linkSource).invoke(new NotebookEntity.SourceLinked("s2", T1));
    testKit.method(NotebookEntity::linkNote).invoke(new NotebookEntity.NoteLinked("n1", T1));

    assertThat(testKit.getState().sourceIds()).containsExactlyInAnyOrder("s1", "s2");
    assertThat(testKit.getState().noteIds()).containsExactly("n1");
  }

  @Test
  void deletedNotebookNoLongerExists() {
    var testKit = kit("nb1");
    testKit.method(NotebookEntity::create).invoke(new NotebookEntity.Create("NB", "d", T0));
    testKit.method(NotebookEntity::delete).invoke(new NotebookEntity.NotebookDeleted(T1));
    assertThat(testKit.getState().exists()).isFalse();
    var afterDelete = testKit.method(NotebookEntity::get).invoke();
    assertThat(afterDelete.isError()).isTrue();
  }
}
