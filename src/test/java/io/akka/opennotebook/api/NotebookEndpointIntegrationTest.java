package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.opennotebook.domain.DeleteResult;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * The HTTP surface end to end — path binding, query-parameter binding, and JSON request/response
 * shapes, none of which a componentClient-only test exercises.
 */
class NotebookEndpointIntegrationTest extends TestKitSupport {

  @Test
  void createIngestAndDeleteANotebookThroughHttp() {
    var notebook =
        httpClient
            .POST("/notebooks")
            .withRequestBody(new NotebookEndpoint.CreateRequest("NB", "desc"))
            .responseBodyAs(NotebookEndpoint.NotebookResponse.class)
            .invoke()
            .body();

    var source =
        httpClient
            .POST("/sources")
            .withRequestBody(
                new SourceEndpoint.CreateRequest(
                    "text", "hello world", null, "my source", List.of(notebook.notebookId())))
            .responseBodyAs(SourceEndpoint.SourceResponse.class)
            .invoke()
            .body();

    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var fetched =
                  httpClient
                      .GET("/sources/" + source.sourceId())
                      .responseBodyAs(SourceEndpoint.SourceResponse.class)
                      .invoke()
                      .body();
              assertThat(fetched.status()).isEqualTo("COMPLETED");
            });

    // The query parameter is what earlier bound as a plain method parameter and never reached
    // the request at all — this confirms it now does.
    var deleteResult =
        httpClient
            .DELETE("/notebooks/" + notebook.notebookId() + "?deleteExclusiveSources=true")
            .responseBodyAs(DeleteResult.class)
            .invoke()
            .body();

    assertThat(deleteResult.deletedSources()).isEqualTo(1);
    assertThat(deleteResult.unlinkedSources()).isEqualTo(0);
  }
}
