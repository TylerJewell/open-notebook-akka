package io.akka.opennotebook.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A source's ingestion lifecycle (R1–R4, R9–R12, R15): submitted, extracted (or failed), and
 * which notebooks it is currently linked to.
 */
public record Source(
    String sourceId,
    String title,
    String url,
    String filePath,
    String fullText,
    SourceStatus status,
    String errorMessage,
    Set<String> notebookIds,
    List<Insight> insights,
    Instant createdAt,
    Instant updatedAt,
    boolean deleted) {

  public static final String PLACEHOLDER_TITLE = "Processing...";

  public static Source createPlaceholder(
      String sourceId,
      String callerTitle,
      String url,
      String filePath,
      Set<String> notebookIds,
      Instant now) {
    String title = (callerTitle == null || callerTitle.isBlank()) ? PLACEHOLDER_TITLE : callerTitle;
    return new Source(
        sourceId,
        title,
        url,
        filePath,
        null,
        SourceStatus.NEW,
        null,
        Set.copyOf(notebookIds),
        List.of(),
        now,
        now,
        false);
  }

  public boolean exists() {
    return sourceId != null && !deleted;
  }

  public Source withRunning(Instant now) {
    return new Source(
        sourceId,
        title,
        url,
        filePath,
        fullText,
        SourceStatus.RUNNING,
        null,
        notebookIds,
        insights,
        createdAt,
        now,
        deleted);
  }

  /** R3: an extracted title only replaces the placeholder, never a caller-supplied one. */
  public Source withExtractionSucceeded(String extractedTitle, String extractedFullText, Instant now) {
    boolean overwritable = title == null || title.isBlank() || title.equals(PLACEHOLDER_TITLE);
    String resolvedTitle =
        (overwritable && extractedTitle != null && !extractedTitle.isBlank())
            ? extractedTitle
            : title;
    return new Source(
        sourceId,
        resolvedTitle,
        url,
        filePath,
        extractedFullText,
        SourceStatus.COMPLETED,
        null,
        notebookIds,
        insights,
        createdAt,
        now,
        deleted);
  }

  /** R4: a failure changes only status and errorMessage; everything else is untouched. */
  public Source withExtractionFailed(String errorMessage, Instant now) {
    return new Source(
        sourceId,
        title,
        url,
        filePath,
        fullText,
        SourceStatus.FAILED,
        errorMessage,
        notebookIds,
        insights,
        createdAt,
        now,
        deleted);
  }

  public Source withNotebookLinked(String notebookId, Instant now) {
    var linked = new java.util.LinkedHashSet<>(notebookIds);
    linked.add(notebookId);
    return new Source(
        sourceId,
        title,
        url,
        filePath,
        fullText,
        status,
        errorMessage,
        linked,
        insights,
        createdAt,
        now,
        deleted);
  }

  /** R10: unlinking a shared source removes only this notebook's membership, nothing else. */
  public Source withNotebookUnlinked(String notebookId, Instant now) {
    var linked = new java.util.LinkedHashSet<>(notebookIds);
    linked.remove(notebookId);
    return new Source(
        sourceId,
        title,
        url,
        filePath,
        fullText,
        status,
        errorMessage,
        linked,
        insights,
        createdAt,
        now,
        deleted);
  }

  public Source withInsightAdded(Insight insight, Instant now) {
    var updated = new java.util.ArrayList<>(insights);
    updated.add(insight);
    return new Source(
        sourceId,
        title,
        url,
        filePath,
        fullText,
        status,
        errorMessage,
        notebookIds,
        List.copyOf(updated),
        createdAt,
        now,
        deleted);
  }

  /** R9: exclusive to a notebook means linked to that one and no other. */
  public boolean isExclusiveTo(String notebookId) {
    return notebookIds.size() == 1 && notebookIds.contains(notebookId);
  }

  /** R12: deleting a source clears its insights along with it. */
  public Source withDeleted(Instant now) {
    return new Source(
        sourceId,
        title,
        url,
        filePath,
        fullText,
        status,
        errorMessage,
        Set.of(),
        List.of(),
        createdAt,
        now,
        true);
  }
}
