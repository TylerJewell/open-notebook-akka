package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code api/routers/insights.py}'s global {@code /insights/{id}} routes -- addressing an
 * insight by its own id, not by its owning source and a list index. */
class ApiInsightEndpointIntegrationTest extends TestKitSupport {

  @Test
  void insightIsReachableGettableAndDeletableByItsOwnGlobalId() {
    var notebook =
        httpClient
            .POST("/api/notebooks")
            .withRequestBody(new ApiNotebookEndpoint.CreateRequest("Insights NB", null))
            .responseBodyAs(ApiNotebookEndpoint.NotebookResponse.class)
            .invoke()
            .body();

    String sourceId = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(sourceId)
        .method(io.akka.opennotebook.application.SourceEntity::createPlaceholder)
        .invoke(
            new io.akka.opennotebook.application.SourceEntity.CreatePlaceholder(
                "Source", null, null, List.of(notebook.id()), Instant.now()));
    componentClient
        .forEventSourcedEntity(sourceId)
        .method(io.akka.opennotebook.application.SourceEntity::addInsight)
        .invoke(new io.akka.opennotebook.application.SourceEntity.AddInsight("summary", "A generated insight.", Instant.now()));

    var listed =
        httpClient
            .GET("/api/sources/" + sourceId + "/insights")
            .responseBodyAs(List.class)
            .invoke()
            .body();
    assertThat(listed).hasSize(1);
    String globalId = (String) ((java.util.Map<?, ?>) listed.get(0)).get("id");
    assertThat(globalId).startsWith(sourceId + ":");

    var fetched =
        httpClient.GET("/api/insights/" + globalId).responseBodyAs(ApiInsightEndpoint.InsightResponse.class).invoke().body();
    assertThat(fetched.source_id()).isEqualTo(sourceId);
    assertThat(fetched.content()).isEqualTo("A generated insight.");

    var note =
        httpClient
            .POST("/api/insights/" + globalId + "/save-as-note")
            .withRequestBody(new ApiInsightEndpoint.SaveAsNoteRequest(notebook.id()))
            .responseBodyAs(ApiInsightEndpoint.NoteResponse.class)
            .invoke()
            .body();
    assertThat(note.content()).isEqualTo("A generated insight.");

    httpClient.DELETE("/api/insights/" + globalId).invoke();
    var afterDelete = httpClient.GET("/api/insights/" + globalId).invoke();
    assertThat(afterDelete.status().intValue()).isEqualTo(404);
  }

  @Test
  void malformedIdWithNoSourcePrefixIsNotFound() {
    var response = httpClient.GET("/api/insights/not-a-composite-id").invoke();
    assertThat(response.status().intValue()).isEqualTo(404);
  }
}
