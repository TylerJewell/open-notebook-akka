package io.akka.opennotebook.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import com.sun.net.httpserver.HttpServer;
import io.akka.opennotebook.domain.ContentSettings;
import io.akka.opennotebook.domain.ExtractionRequest;
import io.akka.opennotebook.domain.SourceStatus;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@link SourceIngestionWorkflow}'s engine-selection wiring (SPEC-001 SS1): a source's URL is
 * fetched through whichever engine {@code content_settings.default_content_processing_engine_url}
 * names, not always the plain fetch -- driven through the real workflow and entity against a
 * loopback server standing in for Firecrawl's own API, at the fixed port {@code
 * FIRECRAWL_API_URL} is pinned to in {@code pom.xml}. */
class SourceIngestionUrlEngineIntegrationTest extends TestKitSupport {

  private static HttpServer server;

  @BeforeAll
  static void startFirecrawlLoopbackServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 26520), 0);
    server.createContext(
        "/v1/scrape",
        exchange -> {
          String body =
              "{\"success\":true,\"data\":{\"markdown\":\"Fetched via the configured engine.\","
                  + "\"metadata\":{\"title\":\"Engine Test\"}}}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  @Test
  void urlSourceIsFetchedThroughTheConfiguredEngineWhenOneIsSet() {
    ContentSettings withFirecrawl =
        new ContentSettings("auto", "firecrawl", "ask", "no", false, false, false, List.of("en"));
    componentClient.forKeyValueEntity(ContentSettingsEntity.ID).method(ContentSettingsEntity::set).invoke(withFirecrawl);

    try {
      String sourceId = UUID.randomUUID().toString();
      componentClient
          .forEventSourcedEntity(sourceId)
          .method(SourceEntity::createPlaceholder)
          .invoke(new SourceEntity.CreatePlaceholder(null, null, null, List.of(), Instant.now()));
      componentClient
          .forWorkflow(sourceId)
          .method(SourceIngestionWorkflow::start)
          .invoke(SourceIngestionWorkflow.Start.of(sourceId, new ExtractionRequest.Url("https://example.com/anything")));

      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                var source = componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
                assertThat(source.status()).isEqualTo(SourceStatus.COMPLETED);
                assertThat(source.title()).isEqualTo("Engine Test");
                assertThat(source.fullText()).isEqualTo("Fetched via the configured engine.");
              });
    } finally {
      componentClient.forKeyValueEntity(ContentSettingsEntity.ID).method(ContentSettingsEntity::set).invoke(ContentSettings.defaults());
    }
  }
}
