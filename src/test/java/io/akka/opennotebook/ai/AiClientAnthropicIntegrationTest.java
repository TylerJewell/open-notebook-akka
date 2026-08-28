package io.akka.opennotebook.ai;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import com.sun.net.httpserver.HttpServer;
import io.akka.opennotebook.api.ApiCredentialEndpoint;
import io.akka.opennotebook.api.ApiModelEndpoint;
import io.akka.opennotebook.api.ApiSearchEndpoint;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@link AiClient}'s Anthropic Messages API translation (SPEC-001 SS6 D-7's proof that the
 * one-HTTP-shape design extends to a genuinely different wire protocol) -- driven end to end
 * through {@code POST /api/search/ask/simple} against a loopback server that speaks Anthropic's
 * own request/response shape, not the OpenAI-compatible one every other provider in this port's
 * tests uses. */
class AiClientAnthropicIntegrationTest extends TestKitSupport {

  private static HttpServer server;
  private static String baseUrl;
  private static volatile String lastRequestBody;
  private static volatile String lastAuthHeader;
  private static volatile String lastVersionHeader;

  @BeforeAll
  static void startAnthropicShapedLoopbackServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/messages",
        exchange -> {
          lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          lastAuthHeader = exchange.getRequestHeaders().getFirst("x-api-key");
          lastVersionHeader = exchange.getRequestHeaders().getFirst("anthropic-version");
          String body = "{\"content\":[{\"type\":\"text\",\"text\":\"An answer from the Anthropic-shaped mock.\"}]}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  @Test
  void chatCompleteTranslatesToAnthropicsOwnMessagesApiShape() {
    var credential =
        httpClient
            .POST("/api/credentials")
            .withRequestBody(
                new ApiCredentialEndpoint.CreateRequest("Anthropic mock", "anthropic", List.of("language"), "sk-ant-test", baseUrl))
            .responseBodyAs(ApiCredentialEndpoint.CredentialResponse.class)
            .invoke()
            .body();
    var model =
        httpClient
            .POST("/api/models")
            .withRequestBody(new ApiModelEndpoint.CreateRequest("claude-mock", "anthropic", "language", credential.id()))
            .responseBodyAs(ApiModelEndpoint.ModelResponse.class)
            .invoke()
            .body();

    var answer =
        httpClient
            .POST("/api/search/ask/simple")
            .withRequestBody(new ApiSearchEndpoint.AskRequest("What shape is this?", model.id(), model.id(), model.id()))
            .responseBodyAs(ApiSearchEndpoint.AskResponse.class)
            .invoke()
            .body();

    assertThat(answer.answer()).isEqualTo("An answer from the Anthropic-shaped mock.");
    // Anthropic's own headers, not a Bearer token -- x-api-key and anthropic-version.
    assertThat(lastAuthHeader).isEqualTo("sk-ant-test");
    assertThat(lastVersionHeader).isEqualTo("2023-06-01");
    // A top-level "system" field, not a system-role message.
    assertThat(lastRequestBody).contains("\"system\"").doesNotContain("\"role\":\"system\"");
  }
}
