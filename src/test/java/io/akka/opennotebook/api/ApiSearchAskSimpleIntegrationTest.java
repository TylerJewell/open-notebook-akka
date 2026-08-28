package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code api/routers/search.py}'s {@code POST /search/ask/simple} -- the non-streaming twin of
 * {@code POST /search/ask}, against the mock provider. */
class ApiSearchAskSimpleIntegrationTest extends TestKitSupport {

  private static final String MOCK_PROVIDER_URL = "http://127.0.0.1:26510";

  @Test
  void askSimpleReturnsAPlainJsonAnswerInsteadOfAnEventStream() {
    var credential =
        httpClient
            .POST("/credentials")
            .withRequestBody(
                new CredentialEndpoint.CreateRequest("Mock", "openai", List.of("language"), "sk-test", MOCK_PROVIDER_URL))
            .responseBodyAs(CredentialEndpoint.CredentialResponse.class)
            .invoke()
            .body();
    var model =
        httpClient
            .POST("/models")
            .withRequestBody(new ModelEndpoint.CreateModelRequest("mock-chat", "openai", "language", credential.id()))
            .responseBodyAs(ModelEndpoint.ModelResponse.class)
            .invoke()
            .body();

    var answer =
        httpClient
            .POST("/api/search/ask/simple")
            .withRequestBody(new ApiSearchEndpoint.AskRequest("What is this about?", model.id(), model.id(), model.id()))
            .responseBodyAs(ApiSearchEndpoint.AskResponse.class)
            .invoke()
            .body();

    assertThat(answer.question()).isEqualTo("What is this about?");
    assertThat(answer.answer()).isNotBlank();
  }
}
