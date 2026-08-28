package io.akka.opennotebook.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import com.sun.net.httpserver.HttpServer;
import io.akka.opennotebook.domain.ExtractionRequest;
import io.akka.opennotebook.domain.LocalFileExtraction;
import io.akka.opennotebook.domain.SourceStatus;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 rules 1, 2, 5, 6, 7 and D-1, driven through the real workflow and entity rather
 * than the domain classes alone.
 */
class SourceIngestionWorkflowIntegrationTest extends TestKitSupport {

  private static HttpServer server;
  private static String baseUrl;

  @BeforeAll
  static void startLoopbackServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/page",
        exchange -> {
          String html =
              "<html><head><title>Example Domain</title></head>"
                  + "<body><script>track()</script><p>This domain is for examples.</p></body></html>";
          byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/html");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stopLoopbackServer() {
    server.stop(0);
  }

  private String submit(ExtractionRequest request) {
    String sourceId = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(sourceId)
        .method(SourceEntity::createPlaceholder)
        .invoke(new SourceEntity.CreatePlaceholder(null, null, null, List.of(), Instant.now()));
    componentClient
        .forWorkflow(sourceId)
        .method(SourceIngestionWorkflow::start)
        .invoke(SourceIngestionWorkflow.Start.of(sourceId, request));
    return sourceId;
  }

  @Test
  void plainTextSourceReachesCompletedWithFullTextVerbatim() {
    String sourceId = submit(new ExtractionRequest.PlainText("The quick brown fox."));

    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var source =
                  componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
              assertThat(source.status()).isEqualTo(SourceStatus.COMPLETED);
              assertThat(source.fullText()).isEqualTo("The quick brown fox.");
            });
  }

  @Test
  void urlSourceExtractsTitleAndVisibleText() {
    String sourceId = submit(new ExtractionRequest.Url(baseUrl + "/page"));

    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var source =
                  componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
              assertThat(source.status()).isEqualTo(SourceStatus.COMPLETED);
              assertThat(source.title()).isEqualTo("Example Domain");
              assertThat(source.fullText())
                  .contains("This domain is for examples.")
                  .doesNotContain("track()");
            });
  }

  @Test
  void unreachableUrlFailsPermanentlyLeavingASingleAttempt() {
    // Nothing listens on this port, so the connection is refused immediately.
    String sourceId = submit(new ExtractionRequest.Url("http://127.0.0.1:1/nope"));

    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var source =
                  componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
              assertThat(source.status()).isEqualTo(SourceStatus.FAILED);
              assertThat(source.errorMessage()).isNotBlank();
              assertThat(source.fullText()).isNull();
            });
  }

  @Test
  void filePathSourceReadsFromTheUploadsDirectory() throws IOException {
    Path uploadsRoot = Path.of(LocalFileExtraction.uploadsRoot()).toAbsolutePath().normalize();
    Files.createDirectories(uploadsRoot);
    Path file = uploadsRoot.resolve("workflow-test.txt");
    Files.writeString(file, "Content read from the uploads directory.");
    try {
      String sourceId = submit(new ExtractionRequest.FilePath(file.toString()));

      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                var source =
                    componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
                assertThat(source.status()).isEqualTo(SourceStatus.COMPLETED);
                assertThat(source.fullText()).isEqualTo("Content read from the uploads directory.");
                // Checked against the real source: a file source with no caller-supplied title
                // is titled by its own filename, not left blank.
                assertThat(source.title()).isEqualTo("workflow-test.txt");
              });
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void filePathOutsideUploadsDirectoryFailsPermanently() throws IOException {
    Path outside = Files.createTempFile("open-notebook-lfi-workflow-", ".txt");
    Files.writeString(outside, "must not be read");
    try {
      String sourceId = submit(new ExtractionRequest.FilePath(outside.toString()));

      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                var source =
                    componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
                assertThat(source.status()).isEqualTo(SourceStatus.FAILED);
                assertThat(source.errorMessage()).contains("uploads directory");
              });
    } finally {
      Files.deleteIfExists(outside);
    }
  }
}
