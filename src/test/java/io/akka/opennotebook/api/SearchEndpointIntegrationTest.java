package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Chunking, embedding, and search end to end, against the mock embeddings endpoint. */
class SearchEndpointIntegrationTest extends TestKitSupport {

  private static final String MOCK_PROVIDER_URL = "http://127.0.0.1:26510";

  @Test
  void vectorizingASourceMakesItFindableBySearch() {
    var credential =
        httpClient
            .POST("/credentials")
            .withRequestBody(
                new CredentialEndpoint.CreateRequest(
                    "Mock", "openai", List.of("embedding"), "sk-test", MOCK_PROVIDER_URL))
            .responseBodyAs(CredentialEndpoint.CredentialResponse.class)
            .invoke()
            .body();
    var model =
        httpClient
            .POST("/models")
            .withRequestBody(
                new ModelEndpoint.CreateModelRequest("mock-embed", "openai", "embedding", credential.id()))
            .responseBodyAs(ModelEndpoint.ModelResponse.class)
            .invoke()
            .body();

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
                    "text", "Deep learning is a subset of machine learning.", null, "s1",
                    List.of(notebook.notebookId())))
            .responseBodyAs(SourceEndpoint.SourceResponse.class)
            .invoke()
            .body();

    org.awaitility.Awaitility.await()
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

    var vectorized =
        httpClient
            .POST("/sources/" + source.sourceId() + "/vectorize")
            .withRequestBody(new SearchEndpoint.VectorizeRequest(model.id()))
            .responseBodyAs(SearchEndpoint.VectorizeResponse.class)
            .invoke()
            .body();
    assertThat(vectorized.chunkCount()).isEqualTo(1);

    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var results =
                  httpClient
                      .POST("/search")
                      .withRequestBody(new SearchEndpoint.SearchRequest("machine learning", model.id(), 5))
                      .responseBodyAs(SearchEndpoint.SearchResponse.class)
                      .invoke()
                      .body();
              assertThat(results.results()).isNotEmpty();
              assertThat(results.results().get(0).ownerId()).isEqualTo(source.sourceId());
            });
  }
}
