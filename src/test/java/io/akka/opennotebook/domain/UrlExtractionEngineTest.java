package io.akka.opennotebook.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The three of the original's four URL-extraction engines that are, underneath {@code
 * content_core}, a plain HTTP call (SPEC-001 SS1) -- driven against real loopback HTTP servers
 * standing in only for Firecrawl's / a remote Crawl4AI server's own network endpoint, at the
 * fixed ports {@code FIRECRAWL_API_URL}/{@code CRAWL4AI_API_URL} are set to in {@code pom.xml},
 * the same fixed-port-mock pattern {@code probes/mock_provider.py} already uses for AI providers.
 * Jina's own endpoint ({@code r.jina.ai}) is not caller-configurable, so only its response
 * parsing ({@link UrlExtractionEngine#parseJinaBody}) is unit-tested here. */
class UrlExtractionEngineTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void jinaBodyWithTitlePrefixSplitsTitleAndContent() {
    var outcome = UrlExtractionEngine.parseJinaBody("Title: Example Domain\nThis domain is for examples.");
    assertThat(outcome).isInstanceOf(Extraction.Outcome.Success.class);
    var success = (Extraction.Outcome.Success) outcome;
    assertThat(success.title()).isEqualTo("Example Domain");
    assertThat(success.content()).isEqualTo("This domain is for examples.");
  }

  @Test
  void jinaBodyWithoutTitlePrefixIsContentOnly() {
    var outcome = UrlExtractionEngine.parseJinaBody("Just plain page content, no title line.");
    assertThat(outcome).isInstanceOf(Extraction.Outcome.Success.class);
    var success = (Extraction.Outcome.Success) outcome;
    assertThat(success.title()).isNull();
    assertThat(success.content()).isEqualTo("Just plain page content, no title line.");
  }

  @Test
  void jinaEmptyBodyIsPermanentFailure() {
    assertThat(UrlExtractionEngine.parseJinaBody("")).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
    assertThat(UrlExtractionEngine.parseJinaBody(null)).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
  }

  @Test
  void firecrawlSuccessResponseIsParsed() throws IOException {
    String body =
        "{\"success\":true,\"data\":{\"markdown\":\"# Example\\nBody text.\",\"metadata\":{\"title\":\"Example Domain\"}}}";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 26520), 0);
    server.createContext(
        "/v1/scrape",
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();

    var outcome = UrlExtractionEngine.viaFirecrawl("https://example.com");
    assertThat(outcome).isInstanceOf(Extraction.Outcome.Success.class);
    var success = (Extraction.Outcome.Success) outcome;
    assertThat(success.title()).isEqualTo("Example Domain");
    assertThat(success.content()).isEqualTo("# Example\nBody text.");
  }

  @Test
  void firecrawlErrorResponseIsUnreachable() throws IOException {
    String body = "{\"success\":false,\"error\":\"Invalid URL\"}";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 26520), 0);
    server.createContext(
        "/v1/scrape",
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(400, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();

    var outcome = UrlExtractionEngine.viaFirecrawl("https://example.com");
    assertThat(outcome).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
    assertThat(((Extraction.Outcome.PermanentFailure) outcome).message()).contains("Invalid URL");
  }

  @Test
  void crawl4aiRemoteSuccessResponseIsParsed() throws IOException {
    String body =
        "{\"results\":[{\"metadata\":{\"title\":\"A Page\"},\"markdown\":{\"raw_markdown\":\"Crawled content.\"}}]}";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 26521), 0);
    server.createContext(
        "/crawl",
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();

    var outcome = UrlExtractionEngine.viaCrawl4aiRemote("https://example.com");
    assertThat(outcome).isInstanceOf(Extraction.Outcome.Success.class);
    var success = (Extraction.Outcome.Success) outcome;
    assertThat(success.title()).isEqualTo("A Page");
    assertThat(success.content()).isEqualTo("Crawled content.");
  }
}
