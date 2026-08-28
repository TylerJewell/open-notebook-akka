package io.akka.opennotebook.application;

import akka.Done;
import akka.javasdk.NotificationPublisher;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.opennotebook.domain.Insight;
import io.akka.opennotebook.domain.Source;
import io.akka.opennotebook.domain.SourceStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A source's ingestion lifecycle (R1–R4, R9–R12, R15) — see SPEC-001.
 *
 * <p>Publishes every event so {@code ApiSourceEndpoint}'s status stream can satisfy RENDERING.md
 * R1: a caller watching a source's processing status subscribes instead of polling.
 */
@Component(id = "source")
public class SourceEntity extends EventSourcedEntity<Source, SourceEntity.Event> {

  private final NotificationPublisher<Event> notificationPublisher;

  public SourceEntity(NotificationPublisher<Event> notificationPublisher) {
    this.notificationPublisher = notificationPublisher;
  }

  public NotificationPublisher.NotificationStream<Event> updates() {
    return notificationPublisher.stream();
  }

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
  public record InsightAdded(String insightId, String insightType, String content, Instant at) implements Event {}

  @TypeName("source-deleted")
  public record SourceDeleted(Instant at) implements Event {}

  @TypeName("source-title-updated")
  public record TitleUpdated(String title, Instant at) implements Event {}

  @TypeName("source-insight-removed")
  public record InsightRemoved(int index, Instant at) implements Event {}

  public record CreatePlaceholder(
      String title, String url, String filePath, List<String> notebookIds, Instant now) {}

  public record AddInsight(String insightType, String content, Instant now) {}

  public record UpdateTitle(String title, Instant now) {}

  public record RemoveInsight(int index, Instant now) {}

  /** {@link io.akka.opennotebook.api.ApiInsightEndpoint}'s global {@code /api/insights/{id}}
   * routes remove by the insight's own id rather than an index a caller has to look up first. */
  public record RemoveInsightById(String insightId, Instant now) {}

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
    return effects().persist(event).thenReply(s -> publish(event));
  }

  public Effect<Done> startRunning(SourceRunning command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> publish(command));
  }

  public Effect<Done> applyExtractionSucceeded(SourceExtractionSucceeded command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> publish(command));
  }

  public Effect<Done> applyExtractionFailed(SourceExtractionFailed command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> publish(command));
  }

  public Effect<Done> addToNotebook(NotebookLinked command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> publish(command));
  }

  public Effect<Done> removeFromNotebook(NotebookUnlinked command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> publish(command));
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
    var event =
        new InsightAdded(
            java.util.UUID.randomUUID().toString(), command.insightType(), command.content(), command.now());
    return effects().persist(event).thenReply(s -> publish(event));
  }

  public Effect<Done> removeInsight(RemoveInsight command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    if (command.index() < 0 || command.index() >= currentState().insights().size()) {
      return effects().error("No such insight");
    }
    var event = new InsightRemoved(command.index(), command.now());
    return effects().persist(event).thenReply(s -> publish(event));
  }

  public Effect<Done> removeInsightById(RemoveInsightById command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    int index = -1;
    var insights = currentState().insights();
    for (int i = 0; i < insights.size(); i++) {
      if (insights.get(i).id().equals(command.insightId())) {
        index = i;
        break;
      }
    }
    if (index < 0) {
      return effects().error("No such insight");
    }
    var event = new InsightRemoved(index, command.now());
    return effects().persist(event).thenReply(s -> publish(event));
  }

  public Effect<Done> updateTitle(UpdateTitle command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    if (command.title() == null || command.title().isBlank()) {
      return effects().error("Title cannot be empty");
    }
    var event = new TitleUpdated(command.title(), command.now());
    return effects().persist(event).thenReply(s -> publish(event));
  }

  public Effect<Done> delete(SourceDeleted command) {
    if (!currentState().exists()) {
      return effects().error("Source not found");
    }
    return effects().persist(command).thenReply(s -> publish(command));
  }

  private Done publish(Event event) {
    notificationPublisher.publish(event);
    return Done.getInstance();
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
      case InsightAdded e ->
          currentState().withInsightAdded(new Insight(e.insightId(), e.insightType(), e.content()), e.at());
      case TitleUpdated e -> currentState().withTitle(e.title(), e.at());
      case InsightRemoved e -> currentState().withInsightRemoved(e.index(), e.at());
      case SourceDeleted e -> currentState().withDeleted(e.at());
    };
  }
}
