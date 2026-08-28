package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.opennotebook.domain.LocalFileExtraction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            .withRequestBody(new ApiSourceEndpoint.CreateRequest("text", "hello world", null, null, "my source", null, notebook.id()))
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
            .withRequestBody(new ApiSourceEndpoint.CreateRequest("text", "primed", null, null, "s", List.of(), null))
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
            .withRequestBody(new ApiSourceEndpoint.CreateRequest("text", "content", null, null, "Original", List.of(), null))
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

  /** The original's "backward compatibility" mode: a {@code file_path} already inside the
   * uploads directory, with no multipart body at all (ApiSourceEndpoint's class doc). */
  @Test
  void fileTypeWithAPathAlreadyInsideUploadsSettlesToCompletedWithItsContent() throws IOException {
    Path uploadsRoot = Path.of(LocalFileExtraction.uploadsRoot()).toAbsolutePath().normalize();
    Files.createDirectories(uploadsRoot);
    Path file = uploadsRoot.resolve("http-test.txt");
    Files.writeString(file, "Read through the HTTP endpoint.");
    try {
      var created =
          httpClient
              .POST("/api/sources")
              .withRequestBody(new ApiSourceEndpoint.CreateRequest("file", null, null, file.toString(), null, List.of(), null))
              .responseBodyAs(ApiSourceEndpoint.SourceDetailResponse.class)
              .invoke()
              .body();
      assertThat(created.asset().file_path()).isEqualTo(file.toString());

      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                var fetched =
                    httpClient
                        .GET("/api/sources/" + created.id())
                        .responseBodyAs(ApiSourceEndpoint.SourceDetailResponse.class)
                        .invoke()
                        .body();
                assertThat(fetched.status()).isEqualTo("completed");
                assertThat(fetched.full_text()).isEqualTo("Read through the HTTP endpoint.");
                assertThat(fetched.title()).isEqualTo("http-test.txt");
              });
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void fileTypeWithoutAPathIsRejected() {
    var response =
        httpClient
            .POST("/api/sources")
            .withRequestBody(new ApiSourceEndpoint.CreateRequest("file", null, null, null, null, List.of(), null))
            .invoke();
    assertThat(response.status().intValue()).isEqualTo(400);
  }

  /** {@code api/routers/sources.py}'s own doc: "legacy endpoint for backward compatibility",
   * a JSON-body alias for the same creation logic. */
  @Test
  void sourcesJsonIsAnAliasForTheSameCreateLogic() {
    var created =
        httpClient
            .POST("/api/sources/json")
            .withRequestBody(new ApiSourceEndpoint.CreateRequest("text", "via json alias", null, null, "t", List.of(), null))
            .responseBodyAs(ApiSourceEndpoint.SourceDetailResponse.class)
            .invoke()
            .body();
    assertThat(created.title()).isEqualTo("t");
  }

  @Test
  void downloadServesTheFileTypeSourcesOwnBytes() throws IOException {
    Path uploadsRoot = Path.of(LocalFileExtraction.uploadsRoot()).toAbsolutePath().normalize();
    Files.createDirectories(uploadsRoot);
    Path file = uploadsRoot.resolve("download-test.bin");
    byte[] fileBytes = {1, 2, 3, 4, 5};
    Files.write(file, fileBytes);
    try {
      var created =
          httpClient
              .POST("/api/sources")
              .withRequestBody(new ApiSourceEndpoint.CreateRequest("file", null, null, file.toString(), null, List.of(), null))
              .responseBodyAs(ApiSourceEndpoint.SourceDetailResponse.class)
              .invoke()
              .body();

      var downloaded = httpClient.GET("/api/sources/" + created.id() + "/download").invoke();
      assertThat(downloaded.status().intValue()).isEqualTo(200);
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void downloadOfASourceWithNoFileIsNotFound() {
    var created =
        httpClient
            .POST("/api/sources")
            .withRequestBody(new ApiSourceEndpoint.CreateRequest("text", "no file here", null, null, "t", List.of(), null))
            .responseBodyAs(ApiSourceEndpoint.SourceDetailResponse.class)
            .invoke()
            .body();
    var response = httpClient.GET("/api/sources/" + created.id() + "/download").invoke();
    assertThat(response.status().intValue()).isEqualTo(404);
  }
}
