package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** RENDERING.md R1/R3/R4 for the frontend's {@code sources.ts}: the list-by-notebook view and the
 * status stream that replaces {@code useSourceStatus}'s poll. */
class ApiSourceEndpointIntegrationTest extends TestKitSupport {

  @Test
  void createdSourceAppearsInTheNotebookListedByAndSettlesToCompleted() {
    var notebook =
        httpClient
            .POST("/api/notebooks")
            .withRequestBody(new ApiNotebookEndpoint.CreateRequest("Sources NB", null))
            .responseBodyAs(ApiNotebookEndpoint.NotebookResponse.class)
            .invoke()
            .body();
    var created =
        httpClient
            .POST("/api/sources")
            .withRequestBody(new ApiSourceEndpoint.CreateRequest("text", "hello world", null, "my source", null, notebook.id()))
            .responseBodyAs(ApiSourceEndpoint.SourceDetailResponse.class)
            .invoke()
            .body();
    assertThat(created.title()).isEqualTo("my source");

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var list =
                  httpClient
                      .GET("/api/sources?notebook_id=" + notebook.id())
                      .responseBodyAs(List.class)
                      .invoke()
                      .body();
              assertThat(list).hasSize(1);
            });

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var status =
                  httpClient
                      .GET("/api/sources/" + created.id() + "/status")
                      .responseBodyAs(ApiSourceEndpoint.SourceStatusResponse.class)
                      .invoke()
                      .body();
              assertThat(status.status()).isEqualTo("completed");
            });
  }

  @Test
  void statusStreamPrimesWithCurrentStateBeforeAnyChange() {
    var created =
        httpClient
            .POST("/api/sources")
            .withRequestBody(new ApiSourceEndpoint.CreateRequest("text", "primed", null, "s", List.of(), null))
            .responseBodyAs(ApiSourceEndpoint.SourceDetailResponse.class)
            .invoke()
            .body();

    var sseRouteTester = testKit.getSelfSseRouteTester();
    var events =
        sseRouteTester.receiveFirstN("/api/sources/" + created.id() + "/status/stream", 1, Duration.ofSeconds(10));
    assertThat(events).hasSize(1);
    // R1.4: the first event is the source's current state, not a wait for the next change.
    assertThat(events.get(0).getData()).contains("\"status\"");
  }

  @Test
  void updatingTitleIsReflectedOnSubsequentGet() {
    var created =
        httpClient
            .POST("/api/sources")
            .withRequestBody(new ApiSourceEndpoint.CreateRequest("text", "content", null, "Original", List.of(), null))
            .responseBodyAs(ApiSourceEndpoint.SourceDetailResponse.class)
            .invoke()
            .body();
    httpClient
        .PUT("/api/sources/" + created.id())
        .withRequestBody(new ApiSourceEndpoint.UpdateRequest("Renamed"))
        .invoke();
    var fetched =
        httpClient.GET("/api/sources/" + created.id()).responseBodyAs(ApiSourceEndpoint.SourceDetailResponse.class).invoke().body();
    assertThat(fetched.title()).isEqualTo("Renamed");
  }
}
