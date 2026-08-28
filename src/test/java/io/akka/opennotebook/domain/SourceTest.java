package io.akka.opennotebook.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SourceTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:05Z");

  @Test
  void createdBeforeExtractionRunsCarriesCallerTitleAndLinks() {
    var source =
        Source.createPlaceholder("s1", "fox source", null, null, Set.of("nb1", "nb2"), T0);

    assertThat(source.title()).isEqualTo("fox source");
    assertThat(source.status()).isEqualTo(SourceStatus.NEW);
    assertThat(source.notebookIds()).containsExactlyInAnyOrder("nb1", "nb2");
    assertThat(source.fullText()).isNull();
  }

  @Test
  void createdWithNoTitleGetsThePlaceholder() {
    var source = Source.createPlaceholder("s1", null, "https://example.com", null, Set.of("nb1"), T0);
    assertThat(source.title()).isEqualTo(Source.PLACEHOLDER_TITLE);
  }

  @Test
  void extractionMovesThroughRunningToCompletedOrFailed() {
    var source = Source.createPlaceholder("s1", null, null, null, Set.of("nb1"), T0);

    var running = source.withRunning(T1);
    assertThat(running.status()).isEqualTo(SourceStatus.RUNNING);

    var completed = running.withExtractionSucceeded("Example", "body text", T1);
    assertThat(completed.status()).isEqualTo(SourceStatus.COMPLETED);
    assertThat(completed.fullText()).isEqualTo("body text");

    var failed = running.withExtractionFailed("boom", T1);
    assertThat(failed.status()).isEqualTo(SourceStatus.FAILED);
    assertThat(failed.errorMessage()).isEqualTo("boom");
  }

  @Test
  void titlePreservedWhenCallerSuppliedOverwrittenWhenPlaceholder() {
    var withCallerTitle =
        Source.createPlaceholder("s1", "fox source", null, null, Set.of("nb1"), T0)
            .withRunning(T1)
            .withExtractionSucceeded("Extracted Title", "text", T1);
    assertThat(withCallerTitle.title()).isEqualTo("fox source");

    var withoutCallerTitle =
        Source.createPlaceholder("s2", null, "https://example.com", null, Set.of("nb1"), T0)
            .withRunning(T1)
            .withExtractionSucceeded("Example Domain", "text", T1);
    assertThat(withoutCallerTitle.title()).isEqualTo("Example Domain");
  }

  @Test
  void failureLeavesTitleFullTextAndLinksUnchanged() {
    var source =
        Source.createPlaceholder("s1", "my title", "https://example.com", null, Set.of("nb1"), T0)
            .withRunning(T1);

    var failed = source.withExtractionFailed("unreachable", T1);

    assertThat(failed.title()).isEqualTo("my title");
    assertThat(failed.fullText()).isNull();
    assertThat(failed.notebookIds()).containsExactly("nb1");
    assertThat(failed.status()).isEqualTo(SourceStatus.FAILED);
    assertThat(failed.errorMessage()).isEqualTo("unreachable");
  }

  @Test
  void linksToMultipleNotebooks() {
    var source = Source.createPlaceholder("s1", null, null, null, Set.of("nb1"), T0);
    var linked = source.withNotebookLinked("nb2", T1);
    assertThat(linked.notebookIds()).containsExactlyInAnyOrder("nb1", "nb2");
    assertThat(linked.isExclusiveTo("nb1")).isFalse();
  }

  @Test
  void exclusiveOnlyWhenLinkedToExactlyThatOneNotebook() {
    var exclusive = Source.createPlaceholder("s1", null, null, null, Set.of("nb1"), T0);
    assertThat(exclusive.isExclusiveTo("nb1")).isTrue();

    var shared = exclusive.withNotebookLinked("nb2", T1);
    assertThat(shared.isExclusiveTo("nb1")).isFalse();

    var unlinkedBackToExclusive = shared.withNotebookUnlinked("nb2", T1);
    assertThat(unlinkedBackToExclusive.isExclusiveTo("nb1")).isTrue();
  }

  @Test
  void deletingSourceClearsInsights() {
    var source =
        Source.createPlaceholder("s1", null, null, null, Set.of("nb1"), T0)
            .withInsightAdded(new Insight("i1", "summary", "content"), T1);
    assertThat(source.insights()).hasSize(1);

    var deleted = source.withDeleted(T1);
    assertThat(deleted.insights()).isEmpty();
    assertThat(deleted.deleted()).isTrue();
  }
}
