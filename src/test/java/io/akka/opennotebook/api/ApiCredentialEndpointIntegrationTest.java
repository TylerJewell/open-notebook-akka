package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code api/routers/credentials.py}'s {@code test}/{@code discover} against the mock provider
 * -- both make a real {@code GET /v1/models} call now ({@link
 * io.akka.opennotebook.ai.AiClient#listModels}), not a hardcoded success or an empty catalog. */
class ApiCredentialEndpointIntegrationTest extends TestKitSupport {

  private static final String MOCK_PROVIDER_URL = "http://127.0.0.1:26510";

  @Test
  void testAndDiscoverSucceedAgainstARealReachableProvider() {
    var credential =
        httpClient
            .POST("/api/credentials")
            .withRequestBody(
                new ApiCredentialEndpoint.CreateRequest("Mock", "openai", List.of("language"), "sk-test", MOCK_PROVIDER_URL))
            .responseBodyAs(ApiCredentialEndpoint.CredentialResponse.class)
            .invoke()
            .body();

    var tested =
        httpClient
            .POST("/api/credentials/" + credential.id() + "/test")
            .responseBodyAs(ApiCredentialEndpoint.TestResponse.class)
            .invoke()
            .body();
    assertThat(tested.success()).isTrue();

    var discovered =
        httpClient
            .POST("/api/credentials/" + credential.id() + "/discover")
            .responseBodyAs(ApiCredentialEndpoint.DiscoverResponse.class)
            .invoke()
            .body();
    assertThat(discovered.discovered()).isNotEmpty();
  }

  @Test
  void testFailsAgainstAnUnreachableBaseUrl() {
    var credential =
        httpClient
            .POST("/api/credentials")
            .withRequestBody(
                new ApiCredentialEndpoint.CreateRequest(
                    "Unreachable", "openai", List.of("language"), "sk-test", "http://127.0.0.1:1"))
            .responseBodyAs(ApiCredentialEndpoint.CredentialResponse.class)
            .invoke()
            .body();

    var tested =
        httpClient
            .POST("/api/credentials/" + credential.id() + "/test")
            .responseBodyAs(ApiCredentialEndpoint.TestResponse.class)
            .invoke()
            .body();
    assertThat(tested.success()).isFalse();
  }
}
