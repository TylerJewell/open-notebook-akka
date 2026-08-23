package io.akka.opennotebook.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.opennotebook.domain.Insight;
import io.akka.opennotebook.domain.Source;
import io.akka.opennotebook.domain.SourceStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** A source's ingestion lifecycle (R1–R4, R9–R12, R15) — see SPEC-001. */
@Component(id = "source")
public class SourceEntity extends EventSourcedEntity<Source, SourceEntity.Event> {

  public sealed interface Event {}

  @TypeName("source-created")
  public record SourceCreated(
      String sourceId,
      String title,
      String url,
      String filePath,
      Set<String> notebookIds,
      Instant at)
      implements Event {}

  @TypeName("source-running")
  public record SourceRunning(Instant at) implements Event {}

  @TypeName("source-extraction-succeeded")
  public record SourceExtractionSucceeded(String extractedTitle, String fullText, Instant at)
      implements Event {}

  @TypeName("source-extraction-failed")
  public record SourceExtractionFailed(String errorMessage, Instant at) implements Event {}

  @TypeName("source-notebook-linked")
  public record NotebookLinked(String notebookId, Instant at) implements Event {}

  @TypeName("source-notebook-unlinked")
  public record NotebookUnlinked(String notebookId, Instant at) implements Event {}

  @TypeName("source-insight-added")
  public record InsightAdded(String insightType, String content, Instant at) implements Event {}

  @TypeName("source-deleted")
  public record SourceDeleted(Instant at) implements Event {}

  public record CreatePlaceholder(
      String title, String url, String filePath, List<String> notebookIds, Instant now) {}

  public record AddInsight(String insightType, String content, Instant now) {}

  @Override
  public Source emptyState() {
    return new Source(
        null, null, null, null, null, null, null, Set.of(), List.of(), null, null, false);
  }

  public Effect<Done> createPlaceholder(CreatePlaceholder command) {
    if (currentState().exists()) {
      return effects().error("Source already exists");
    }
    var event =
        new SourceCreated(
            commandContext().entityId(),
            command.title(),
            command.url(),
            command.filePath(),
            Set.copyOf(command.notebookIds()),
            command.now());
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> startRunning(SourceRunning command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> applyExtractionSucceeded(SourceExtractionSucceeded command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> applyExtractionFailed(SourceExtractionFailed command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> addToNotebook(NotebookLinked command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> removeFromNotebook(NotebookUnlinked command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> addInsight(AddInsight command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    if (command.insightType() == null
        || command.insightType().isBlank()
        || command.content() == null
        || command.content().isBlank()) {
      return effects().error("Insight type and content must be provided");
    }
    var event = new InsightAdded(command.insightType(), command.content(), command.now());
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> delete(SourceDeleted command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public ReadOnlyEffect<Source> get() {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().reply(currentState());
  }

  @Override
  public Source applyEvent(Event event) {
    return switch (event) {
      case SourceCreated e ->
          Source.createPlaceholder(
              e.sourceId(), e.title(), e.url(), e.filePath(), e.notebookIds(), e.at());
      case SourceRunning e -> currentState().withRunning(e.at());
      case SourceExtractionSucceeded e ->
          currentState().withExtractionSucceeded(e.extractedTitle(), e.fullText(), e.at());
      case SourceExtractionFailed e -> currentState().withExtractionFailed(e.errorMessage(), e.at());
      case NotebookLinked e -> currentState().withNotebookLinked(e.notebookId(), e.at());
      case NotebookUnlinked e -> currentState().withNotebookUnlinked(e.notebookId(), e.at());
      case InsightAdded e -> currentState().withInsightAdded(new Insight(e.insightType(), e.content()), e.at());
      case SourceDeleted e -> currentState().withDeleted(e.at());
    };
  }
}
