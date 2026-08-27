package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** RENDERING.md R3/R4's data-layer adapter for the frontend's {@code notebooks.ts}: the list
 * view neither NotebookEndpoint nor any test previously exercised, and the snake_case wire shape
 * the vendored frontend actually reads. */
class ApiNotebookEndpointIntegrationTest extends TestKitSupport {

  @Test
  void createdNotebookAppearsInTheListAndSurvivesUpdate() {
    var created =
        httpClient
            .POST("/api/notebooks")
            .withRequestBody(new ApiNotebookEndpoint.CreateRequest("API Notebook", "desc"))
            .responseBodyAs(ApiNotebookEndpoint.NotebookResponse.class)
            .invoke()
            .body();
    assertThat(created.name()).isEqualTo("API Notebook");
    assertThat(created.archived()).isFalse();

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var list = httpClient.GET("/api/notebooks").responseBodyAs(List.class).invoke().body();
              assertThat(list).isNotEmpty();
            });

    var updated =
        httpClient
            .PUT("/api/notebooks/" + created.id())
            .withRequestBody(new ApiNotebookEndpoint.UpdateRequest("Renamed", null, true))
            .responseBodyAs(ApiNotebookEndpoint.NotebookResponse.class)
            .invoke()
            .body();
    assertThat(updated.name()).isEqualTo("Renamed");
    assertThat(updated.archived()).isTrue();

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var archivedList =
                  httpClient.GET("/api/notebooks?archived=true").responseBodyAs(List.class).invoke().body();
              assertThat(archivedList).isNotEmpty();
            });
  }

  @Test
  void addingAndRemovingASourceLinkUpdatesBothSides() {
    var notebook =
        httpClient
            .POST("/api/notebooks")
            .withRequestBody(new ApiNotebookEndpoint.CreateRequest("Linking", null))
            .responseBodyAs(ApiNotebookEndpoint.NotebookResponse.class)
            .invoke()
            .body();
    var source =
        httpClient
            .POST("/api/sources")
            .withRequestBody(new ApiSourceEndpoint.CreateRequest("text", "hello", null, "t", List.of(), null))
            .responseBodyAs(ApiSourceEndpoint.SourceDetailResponse.class)
            .invoke()
            .body();

    httpClient.POST("/api/notebooks/" + notebook.id() + "/sources/" + source.id()).invoke();
    var withSource =
        httpClient.GET("/api/notebooks/" + notebook.id()).responseBodyAs(ApiNotebookEndpoint.NotebookResponse.class).invoke().body();
    assertThat(withSource.source_count()).isEqualTo(1);

    httpClient.DELETE("/api/notebooks/" + notebook.id() + "/sources/" + source.id()).invoke();
    var afterRemove =
        httpClient
            .GET("/api/sources/" + source.id())
            .responseBodyAs(ApiSourceEndpoint.SourceDetailResponse.class)
            .invoke()
            .body();
    assertThat(afterRemove.notebooks()).doesNotContain(notebook.id());
  }
}
