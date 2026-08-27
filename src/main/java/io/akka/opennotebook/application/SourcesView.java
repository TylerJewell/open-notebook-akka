package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every source, queryable by the notebook it is linked to — the frontend's {@code GET
 * /api/sources?notebook_id=} list, which {@code SourceEndpoint} never needed because the bare-path
 * surface only ever addresses one source at a time by id.
 *
 * <p>{@code url}/{@code filePath}/{@code fullText}/{@code errorMessage} are {@code
 * Optional<String>}, not plain {@code String}: a View row's storage schema treats a bare
 * reference-type field as required despite {@code Optional Fields} in the SDK docs describing
 * plain nullable types as supported for view <em>queries</em> generally — the first write with
 * one of these null (every source starts with three of the four unset) failed at the storage
 * layer with {@code AK-00111 ... expected to be non-optional [string] but is missing} and the
 * runtime retried the same event forever rather than surfacing it as a startup-time error.
 * {@code toApi} in {@code ApiSourceEndpoint} unwraps back to a plain nullable field for the wire
 * response, so this is purely a Views-storage detail, not a change to the HTTP contract.
 */
@Component(id = "sources-view")
public class SourcesView extends View {

  public record Entry(
      String sourceId,
      String title,
      Optional<String> url,
      Optional<String> filePath,
      Optional<String> fullText,
      String status,
      Optional<String> errorMessage,
      List<String> notebookIds,
      int insightsCount,
      Instant createdAt,
      Instant updatedAt) {}

  public record Entries(List<Entry> items) {}

  @Consume.FromEventSourcedEntity(SourceEntity.class)
  public static class Sources extends TableUpdater<Entry> {
    public Effect<Entry> onEvent(SourceEntity.Event event) {
      return switch (event) {
        case SourceEntity.SourceCreated e ->
            effects()
                .updateRow(
                    new Entry(
                        e.sourceId(), placeholderIfBlank(e.title()), Optional.ofNullable(e.url()), Optional.ofNullable(e.filePath()),
                        Optional.empty(), "NEW", Optional.empty(),
                        List.copyOf(e.notebookIds()), 0, e.at(), e.at()));
        case SourceEntity.SourceRunning e ->
            effects().updateRow(withStatus("RUNNING", Optional.empty(), e.at()));
        case SourceEntity.SourceExtractionSucceeded e ->
            effects()
                .updateRow(
                    new Entry(
                        rowState().sourceId(),
                        overwritable(rowState().title()) && e.extractedTitle() != null && !e.extractedTitle().isBlank()
                            ? e.extractedTitle()
                            : rowState().title(),
                        rowState().url(), rowState().filePath(), Optional.ofNullable(e.fullText()), "COMPLETED", Optional.empty(),
                        rowState().notebookIds(), rowState().insightsCount(), rowState().createdAt(), e.at()));
        case SourceEntity.SourceExtractionFailed e ->
            effects().updateRow(withStatus("FAILED", Optional.ofNullable(e.errorMessage()), e.at()));
        case SourceEntity.NotebookLinked e -> {
          var linked = new ArrayList<>(rowState().notebookIds());
          if (!linked.contains(e.notebookId())) linked.add(e.notebookId());
          yield effects().updateRow(withNotebooks(linked, e.at()));
        }
        case SourceEntity.NotebookUnlinked e -> {
          var linked = new ArrayList<>(rowState().notebookIds());
          linked.remove(e.notebookId());
          yield effects().updateRow(withNotebooks(linked, e.at()));
        }
        case SourceEntity.InsightAdded e ->
            effects()
                .updateRow(
                    new Entry(
                        rowState().sourceId(), rowState().title(), rowState().url(), rowState().filePath(),
                        rowState().fullText(), rowState().status(), rowState().errorMessage(),
                        rowState().notebookIds(), rowState().insightsCount() + 1, rowState().createdAt(), e.at()));
        case SourceEntity.InsightRemoved e ->
            effects()
                .updateRow(
                    new Entry(
                        rowState().sourceId(), rowState().title(), rowState().url(), rowState().filePath(),
                        rowState().fullText(), rowState().status(), rowState().errorMessage(),
                        rowState().notebookIds(), Math.max(0, rowState().insightsCount() - 1), rowState().createdAt(), e.at()));
        case SourceEntity.TitleUpdated e ->
            effects()
                .updateRow(
                    new Entry(
                        rowState().sourceId(), e.title(), rowState().url(), rowState().filePath(),
                        rowState().fullText(), rowState().status(), rowState().errorMessage(),
                        rowState().notebookIds(), rowState().insightsCount(), rowState().createdAt(), e.at()));
        case SourceEntity.SourceDeleted e -> effects().deleteRow();
      };
    }

    private boolean overwritable(String title) {
      return title == null || title.isBlank() || title.equals("Processing...");
    }

    /** Mirrors {@code Source.createPlaceholder}'s null/blank -> placeholder resolution: the
     * entity's own materialized state never has a null title, but the raw {@code SourceCreated}
     * event this view consumes can (a caller may omit one), and a View row's title column, like
     * every other bare {@code String} field here, cannot store null (see class doc). */
    private static String placeholderIfBlank(String title) {
      return (title == null || title.isBlank()) ? "Processing..." : title;
    }

    private Entry withStatus(String status, Optional<String> errorMessage, Instant at) {
      return new Entry(
          rowState().sourceId(), rowState().title(), rowState().url(), rowState().filePath(),
          rowState().fullText(), status, errorMessage, rowState().notebookIds(),
          rowState().insightsCount(), rowState().createdAt(), at);
    }

    private Entry withNotebooks(List<String> notebookIds, Instant at) {
      return new Entry(
          rowState().sourceId(), rowState().title(), rowState().url(), rowState().filePath(),
          rowState().fullText(), rowState().status(), rowState().errorMessage(), List.copyOf(notebookIds),
          rowState().insightsCount(), rowState().createdAt(), at);
    }
  }

  // No ORDER BY: the real runtime requires it to match the WHERE-filtered column, and this
  // filters on notebookIds, not updatedAt (see all()'s comment). ApiSourceEndpoint sorts client-side.
  @Query("SELECT * AS items FROM sources WHERE :notebookId = ANY(notebookIds)")
  public QueryEffect<Entries> byNotebook(String notebookId) {
    return queryResult();
  }

  // See NotebooksView.all()'s comment: no WHERE, so no ORDER BY -- the real runtime rejects it.
  @Query("SELECT * AS items FROM sources")
  public QueryEffect<Entries> all() {
    return queryResult();
  }
}
