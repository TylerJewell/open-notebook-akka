package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** RENDERING.md R3/R4's data-layer adapter for the frontend's {@code notes.ts}: create, list by
 * notebook, update (R13's content-must-not-be-blank rule carried through the new update path
 * too), and delete -- none of which the bare-path {@code NoteEndpoint} exposed. */
class ApiNoteEndpointIntegrationTest extends TestKitSupport {

  @Test
  void createListUpdateAndDeleteANote() {
    var notebook =
        httpClient
            .POST("/api/notebooks")
            .withRequestBody(new ApiNotebookEndpoint.CreateRequest("Notes NB", null))
            .responseBodyAs(ApiNotebookEndpoint.NotebookResponse.class)
            .invoke()
            .body();

    var created =
        httpClient
            .POST("/api/notes")
            .withRequestBody(new ApiNoteEndpoint.CreateRequest("Title", "Body", "human", notebook.id()))
            .responseBodyAs(ApiNoteEndpoint.NoteResponse.class)
            .invoke()
            .body();
    assertThat(created.content()).isEqualTo("Body");
    assertThat(created.note_type()).isEqualTo("human");

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var list =
                  httpClient.GET("/api/notes?notebook_id=" + notebook.id()).responseBodyAs(List.class).invoke().body();
              assertThat(list).hasSize(1);
            });

    var updated =
        httpClient
            .PUT("/api/notes/" + created.id())
            .withRequestBody(new ApiNoteEndpoint.UpdateRequest(null, "Updated body"))
            .responseBodyAs(ApiNoteEndpoint.NoteResponse.class)
            .invoke()
            .body();
    assertThat(updated.content()).isEqualTo("Updated body");
    assertThat(updated.title()).isEqualTo("Title");

    var blankRejected =
        httpClient
            .PUT("/api/notes/" + created.id())
            .withRequestBody(new ApiNoteEndpoint.UpdateRequest(null, "   "))
            .invoke();
    assertThat(blankRejected.status().intValue()).isEqualTo(400);

    httpClient.DELETE("/api/notes/" + created.id()).invoke();
    var afterDelete = httpClient.GET("/api/notes/" + created.id()).invoke();
    assertThat(afterDelete.status().intValue()).isEqualTo(404);
  }
}
