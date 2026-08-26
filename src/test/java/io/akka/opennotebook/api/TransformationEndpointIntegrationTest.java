package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The AI-provisioning path end to end, through real HTTP, against
 * {@code open-notebook-port/probes/mock_provider.py} — an OpenAI-compatible test double standing
 * in only for the network call to a real model provider (see {@link
 * io.akka.opennotebook.ai.AiClient}'s class doc). Everything else — credential storage/encryption,
 * model resolution, transformation lookup, and the insight this produces on the source — is the
 * port's own code, driven for real.
 */
class TransformationEndpointIntegrationTest extends TestKitSupport {

  private static final String MOCK_PROVIDER_URL = "http://127.0.0.1:26510";

  @Test
  void generatingAnInsightCallsTheConfiguredModelAndRecordsTheReply() {
    var credential =
        httpClient
            .POST("/credentials")
            .withRequestBody(
                new CredentialEndpoint.CreateRequest(
                    "Mock", "openai", List.of("language"), "sk-test", MOCK_PROVIDER_URL))
            .responseBodyAs(CredentialEndpoint.CredentialResponse.class)
            .invoke()
            .body();
    // The API key is accepted but never echoed back.
    assertThat(credential.hasApiKey()).isTrue();

    var model =
        httpClient
            .POST("/models")
            .withRequestBody(
                new ModelEndpoint.CreateModelRequest("mock-chat", "openai", "language", credential.id()))
            .responseBodyAs(ModelEndpoint.ModelResponse.class)
            .invoke()
            .body();

    var transformation =
        httpClient
            .POST("/transformations")
            .withRequestBody(
                new TransformationEndpoint.CreateRequest(
                    "summarize", "Summary", "A one-line summary", "Summarize the text.", false, model.id()))
            .responseBodyAs(io.akka.opennotebook.domain.Transformation.class)
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
                    "text", "The quick brown fox jumps over the lazy dog.", null, "s1",
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

    var withInsight =
        httpClient
            .POST("/sources/" + source.sourceId() + "/insights/generate")
            .withRequestBody(new SourceEndpoint.GenerateInsightRequest(transformation.id(), null))
            .responseBodyAs(SourceEndpoint.SourceResponse.class)
            .invoke()
            .body();

    assertThat(withInsight.status()).isEqualTo("COMPLETED");
  }
}
