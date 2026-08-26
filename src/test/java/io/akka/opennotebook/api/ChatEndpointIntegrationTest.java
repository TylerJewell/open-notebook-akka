package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Chat and ask end to end, against the mock OpenAI-compatible provider. */
class ChatEndpointIntegrationTest extends TestKitSupport {

  private static final String MOCK_PROVIDER_URL = "http://127.0.0.1:26510";

  private String chatModelId() {
    var credential =
        httpClient
            .POST("/credentials")
            .withRequestBody(
                new CredentialEndpoint.CreateRequest(
                    "Mock", "openai", List.of("language"), "sk-test", MOCK_PROVIDER_URL))
            .responseBodyAs(CredentialEndpoint.CredentialResponse.class)
            .invoke()
            .body();
    var model =
        httpClient
            .POST("/models")
            .withRequestBody(
                new ModelEndpoint.CreateModelRequest("mock-chat", "openai", "language", credential.id()))
            .responseBodyAs(ModelEndpoint.ModelResponse.class)
            .invoke()
            .body();
    return model.id();
  }

  @Test
  void chattingAppendsUserAndAssistantMessages() {
    var notebook =
        httpClient
            .POST("/notebooks")
            .withRequestBody(new NotebookEndpoint.CreateRequest("NB", "desc"))
            .responseBodyAs(NotebookEndpoint.NotebookResponse.class)
            .invoke()
            .body();
    String modelId = chatModelId();

    var session =
        httpClient
            .POST("/notebooks/" + notebook.notebookId() + "/chat/sessions")
            .responseBodyAs(ChatEndpoint.CreateSessionResponse.class)
            .invoke()
            .body();

    var afterMessage =
        httpClient
            .POST("/notebooks/" + notebook.notebookId() + "/chat/sessions/" + session.chatId() + "/messages")
            .withRequestBody(new ChatEndpoint.SendMessageRequest("Hello there", modelId))
            .responseBodyAs(ChatEndpoint.SessionResponse.class)
            .invoke()
            .body();

    assertThat(afterMessage.messages()).hasSize(2);
    assertThat(afterMessage.messages().get(0).role()).isEqualTo("user");
    assertThat(afterMessage.messages().get(1).role()).isEqualTo("assistant");
    assertThat(afterMessage.messages().get(1).content()).isNotBlank();
  }

  @Test
  void askAnswersWithoutPersistingASession() {
    var notebook =
        httpClient
            .POST("/notebooks")
            .withRequestBody(new NotebookEndpoint.CreateRequest("NB2", "desc"))
            .responseBodyAs(NotebookEndpoint.NotebookResponse.class)
            .invoke()
            .body();
    String modelId = chatModelId();

    var answer =
        httpClient
            .POST("/notebooks/" + notebook.notebookId() + "/ask")
            .withRequestBody(new ChatEndpoint.AskRequest("What is this notebook about?", modelId))
            .responseBodyAs(ChatEndpoint.AskResponse.class)
            .invoke()
            .body();

    assertThat(answer.answer()).isNotBlank();
  }
}
