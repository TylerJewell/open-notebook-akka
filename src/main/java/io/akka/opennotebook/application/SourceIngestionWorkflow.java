package io.akka.opennotebook.application;

import static java.time.Duration.ofSeconds;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import io.akka.opennotebook.domain.Extraction;
import io.akka.opennotebook.domain.ExtractionRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

/**
 * R1, R2, D-3: extraction runs outside the entity — this workflow performs the fetch (or hands
 * plain text straight through) and then reports the outcome to {@link SourceEntity}, which is
 * the only thing that changes the entity's own state.
 */
@Component(id = "source-ingestion")
public class SourceIngestionWorkflow extends Workflow<SourceIngestionWorkflow.State> {

  // Flattened rather than carrying ExtractionRequest directly: Akka's own serializer resolves a
  // @TypeName-tagged sealed interface only at the top level of a persisted type (an entity's
  // Event, or a command/state record itself), not for one nested inside another record's field.
  public record State(String sourceId, String content, String url) {
    ExtractionRequest request() {
      return content != null ? new ExtractionRequest.PlainText(content) : new ExtractionRequest.Url(url);
    }
  }

  public record Start(String sourceId, String content, String url) {
    public static Start of(String sourceId, ExtractionRequest request) {
      return switch (request) {
        case ExtractionRequest.PlainText plainText -> new Start(sourceId, plainText.content(), null);
        case ExtractionRequest.Url u -> new Start(sourceId, null, u.url());
      };
    }
  }

  private final ComponentClient componentClient;

  public SourceIngestionWorkflow(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(ofSeconds(30)).build();
  }

  public Effect<Done> start(Start command) {
    return effects()
        .updateState(new State(command.sourceId(), command.content(), command.url()))
        .transitionTo(SourceIngestionWorkflow::extractStep)
        .thenReply(Done.getInstance());
  }

  @StepName("extract")
  private StepEffect extractStep() {
    componentClient
        .forEventSourcedEntity(currentState().sourceId())
        .method(SourceEntity::startRunning)
        .invoke(new SourceEntity.SourceRunning(Instant.now()));

    Extraction.Outcome outcome = runExtraction(currentState().request());

    if (outcome instanceof Extraction.Outcome.Success success) {
      componentClient
          .forEventSourcedEntity(currentState().sourceId())
          .method(SourceEntity::applyExtractionSucceeded)
          .invoke(
              new SourceEntity.SourceExtractionSucceeded(
                  success.title(), success.content(), Instant.now()));
    } else if (outcome instanceof Extraction.Outcome.PermanentFailure failure) {
      componentClient
          .forEventSourcedEntity(currentState().sourceId())
          .method(SourceEntity::applyExtractionFailed)
          .invoke(new SourceEntity.SourceExtractionFailed(failure.message(), Instant.now()));
    }

    return stepEffects().thenEnd();
  }

  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().connectTimeout(ofSeconds(10)).build();

  /** R6, R7: plain text passes through verbatim; a URL is fetched and reduced to title + text. */
  private Extraction.Outcome runExtraction(ExtractionRequest request) {
    return switch (request) {
      case ExtractionRequest.PlainText plainText -> Extraction.plainText(plainText.content());
      case ExtractionRequest.Url url -> fetchAndExtract(url.url());
    };
  }

  private Extraction.Outcome fetchAndExtract(String url) {
    try {
      var httpRequest =
          HttpRequest.newBuilder(URI.create(url)).timeout(ofSeconds(15)).GET().build();
      var response = HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Extraction.unreachable("HTTP " + response.statusCode());
      }
      return Extraction.fromFetchedHtml(response.body());
    } catch (Exception e) {
      return Extraction.unreachable(e.getClass().getSimpleName());
    }
  }
}
