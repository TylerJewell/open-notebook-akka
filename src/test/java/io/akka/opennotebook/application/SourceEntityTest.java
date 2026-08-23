package io.akka.opennotebook.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.opennotebook.domain.Source;
import io.akka.opennotebook.domain.SourceStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 1–4, 9–12, 15 — a source's own ingestion lifecycle. */
class SourceEntityTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:05Z");

  private static EventSourcedTestKit<Source, SourceEntity.Event, SourceEntity> kit(String id) {
    return EventSourcedTestKit.of(id, SourceEntity::new);
  }

  @Test
  void createdBeforeExtractionRunsCarriesLinksAndNewStatus() {
    var testKit = kit("s1");
    testKit
        .method(SourceEntity::createPlaceholder)
        .invoke(new SourceEntity.CreatePlaceholder("fox source", null, null, List.of("nb1"), T0));

    assertThat(testKit.getState().status()).isEqualTo(SourceStatus.NEW);
    assertThat(testKit.getState().title()).isEqualTo("fox source");
    assertThat(testKit.getState().notebookIds()).containsExactly("nb1");
  }

  @Test
  void extractionMovesThroughRunningToCompletedOrFailed() {
    var testKit = kit("s1");
    testKit
        .method(SourceEntity::createPlaceholder)
        .invoke(new SourceEntity.CreatePlaceholder(null, null, null, List.of("nb1"), T0));

    testKit.method(SourceEntity::startRunning).invoke(new SourceEntity.SourceRunning(T1));
    assertThat(testKit.getState().status()).isEqualTo(SourceStatus.RUNNING);

    testKit
        .method(SourceEntity::applyExtractionSucceeded)
        .invoke(new SourceEntity.SourceExtractionSucceeded("Example", "body text", T1));
    assertThat(testKit.getState().status()).isEqualTo(SourceStatus.COMPLETED);
    assertThat(testKit.getState().fullText()).isEqualTo("body text");
  }

  @Test
  void titlePreservedWhenCallerSuppliedOverwrittenWhenPlaceholder() {
    var withTitle = kit("s1");
    withTitle
        .method(SourceEntity::createPlaceholder)
        .invoke(new SourceEntity.CreatePlaceholder("fox source", null, null, List.of("nb1"), T0));
    withTitle
        .method(SourceEntity::applyExtractionSucceeded)
        .invoke(new SourceEntity.SourceExtractionSucceeded("Extracted Title", "text", T1));
    assertThat(withTitle.getState().title()).isEqualTo("fox source");

    var withoutTitle = kit("s2");
    withoutTitle
        .method(SourceEntity::createPlaceholder)
        .invoke(new SourceEntity.CreatePlaceholder(null, "https://example.com", null, List.of("nb1"), T0));
    withoutTitle
        .method(SourceEntity::applyExtractionSucceeded)
        .invoke(new SourceEntity.SourceExtractionSucceeded("Example Domain", "text", T1));
    assertThat(withoutTitle.getState().title()).isEqualTo("Example Domain");
  }

  @Test
  void failureLeavesTitleFullTextAndLinksUnchanged() {
    var testKit = kit("s1");
    testKit
        .method(SourceEntity::createPlaceholder)
        .invoke(
            new SourceEntity.CreatePlaceholder(
                "my title", "https://example.com", null, List.of("nb1"), T0));

    testKit
        .method(SourceEntity::applyExtractionFailed)
        .invoke(new SourceEntity.SourceExtractionFailed("unreachable", T1));

    assertThat(testKit.getState().title()).isEqualTo("my title");
    assertThat(testKit.getState().fullText()).isNull();
    assertThat(testKit.getState().notebookIds()).containsExactly("nb1");
    assertThat(testKit.getState().status()).isEqualTo(SourceStatus.FAILED);
    assertThat(testKit.getState().errorMessage()).isEqualTo("unreachable");
  }

  @Test
  void linksToMultipleNotebooks() {
    var testKit = kit("s1");
    testKit
        .method(SourceEntity::createPlaceholder)
        .invoke(new SourceEntity.CreatePlaceholder(null, null, null, List.of("nb1"), T0));
    testKit.method(SourceEntity::addToNotebook).invoke(new SourceEntity.NotebookLinked("nb2", T1));
    assertThat(testKit.getState().notebookIds()).containsExactlyInAnyOrder("nb1", "nb2");
  }

  @Test
  void unlinkingLeavesTheOtherNotebooksIntact() {
    var testKit = kit("s1");
    testKit
        .method(SourceEntity::createPlaceholder)
        .invoke(new SourceEntity.CreatePlaceholder(null, null, null, List.of("nb1", "nb2"), T0));
    testKit
        .method(SourceEntity::removeFromNotebook)
        .invoke(new SourceEntity.NotebookUnlinked("nb2", T1));
    assertThat(testKit.getState().notebookIds()).containsExactly("nb1");
  }

  @Test
  void deletingSourceClearsInsights() {
    var testKit = kit("s1");
    testKit
        .method(SourceEntity::createPlaceholder)
        .invoke(new SourceEntity.CreatePlaceholder(null, null, null, List.of("nb1"), T0));
    testKit.method(SourceEntity::addInsight).invoke(new SourceEntity.AddInsight("summary", "text", T1));
    assertThat(testKit.getState().insights()).hasSize(1);

    testKit.method(SourceEntity::delete).invoke(new SourceEntity.SourceDeleted(T1));
    assertThat(testKit.getState().insights()).isEmpty();
    assertThat(testKit.getState().deleted()).isTrue();
  }

  @Test
  void addingInsightWithNoContentIsRejected() {
    var testKit = kit("s1");
    testKit
        .method(SourceEntity::createPlaceholder)
        .invoke(new SourceEntity.CreatePlaceholder(null, null, null, List.of("nb1"), T0));
    var result =
        testKit.method(SourceEntity::addInsight).invoke(new SourceEntity.AddInsight("", "", T1));
    assertThat(result.isError()).isTrue();
  }
}
