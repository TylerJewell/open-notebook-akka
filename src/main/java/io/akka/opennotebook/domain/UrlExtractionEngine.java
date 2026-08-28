package io.akka.opennotebook.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The three of the original's four alternative URL-extraction engines that are, underneath
 * {@code content_core}, a plain HTTP call this port can make for real -- Jina Reader, Firecrawl,
 * and a remote/self-hosted Crawl4AI server -- selected the same way the original selects them,
 * by {@code content_settings.default_content_processing_engine_url} ({@code "auto"} still means
 * the plain fetch {@link Extraction#fromFetchedHtml}, unchanged).
 *
 * <p><b>Not the fourth:</b> Crawl4AI's own <i>local</i> mode is browser automation (a bundled
 * Chromium via Playwright) with no remote counterpart when {@code CRAWL4AI_API_URL} is unset --
 * an ML/browser runtime this port does not embed, the same infrastructure class as Docling's OCR.
 * Selecting {@code "crawl4ai"} without that variable configured is reported as exactly that gap,
 * not silently downgraded to the plain fetch.
 */
public final class UrlExtractionEngine {

  private UrlExtractionEngine() {}

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  /** Jina Reader: {@code GET https://r.jina.ai/<url>}, an optional Bearer token, and a plain-text
   * body that starts {@code "Title: ...\n"} when Jina found one. */
  public static Extraction.Outcome viaJina(String url) {
    String apiKey = System.getenv("JINA_API_KEY");
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create("https://r.jina.ai/" + url)).timeout(Duration.ofSeconds(30)).GET();
      if (apiKey != null && !apiKey.isBlank()) {
        builder.header("Authorization", "Bearer " + apiKey);
      }
      HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        return Extraction.unreachable("Jina HTTP " + response.statusCode());
      }
      return parseJinaBody(response.body());
    } catch (Exception e) {
      return Extraction.unreachable(e.getClass().getSimpleName());
    }
  }

  /** Split out from {@link #viaJina} so the parsing rule (a leading {@code "Title: ...\n"} line
   * is optional) is unit-testable without a network call to Jina's fixed, non-configurable
   * {@code r.jina.ai} endpoint. */
  static Extraction.Outcome parseJinaBody(String body) {
    if (body == null || body.isBlank()) {
      return new Extraction.Outcome.PermanentFailure(
          "Could not extract any text content from this source. The content may be empty, "
              + "inaccessible, or in an unsupported format.");
    }
    if (body.startsWith("Title:") && body.contains("\n")) {
      int titleEnd = body.indexOf('\n');
      return new Extraction.Outcome.Success(
          body.substring(6, titleEnd).trim(), body.substring(titleEnd + 1).trim());
    }
    return new Extraction.Outcome.Success(null, body.trim());
  }

  /** Firecrawl's {@code POST /v1/scrape}: {@code {"success":true,"data":{"markdown":...,
   * "metadata":{"title":...}}}} on success, {@code {"error":...}} otherwise. */
  public static Extraction.Outcome viaFirecrawl(String url) {
    String apiUrl = System.getenv("FIRECRAWL_API_URL");
    if (apiUrl == null || apiUrl.isBlank()) apiUrl = "https://api.firecrawl.dev";
    String apiKey = System.getenv("FIRECRAWL_API_KEY");

    ObjectNode body = MAPPER.createObjectNode();
    body.put("url", url);
    body.putArray("formats").add("markdown");

    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(apiUrl.replaceAll("/$", "") + "/v1/scrape"))
              .timeout(Duration.ofSeconds(60))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
      if (apiKey != null && !apiKey.isBlank()) {
        builder.header("Authorization", "Bearer " + apiKey);
      }
      HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      JsonNode root = MAPPER.readTree(response.body());
      if (response.statusCode() >= 300 || !root.path("success").asBoolean(false)) {
        String error = root.path("error").asText(response.body());
        return Extraction.unreachable("Firecrawl: " + error);
      }
      JsonNode data = root.path("data");
      String title = data.path("metadata").path("title").asText(null);
      String markdown = data.path("markdown").asText(null);
      if (markdown == null || markdown.isBlank()) {
        return new Extraction.Outcome.PermanentFailure(
            "Could not extract any text content from this source. The content may be empty, "
                + "inaccessible, or in an unsupported format.");
      }
      return new Extraction.Outcome.Success(title, markdown);
    } catch (Exception e) {
      return Extraction.unreachable(e.getClass().getSimpleName());
    }
  }

  /** A remote/Docker Crawl4AI server's {@code POST /crawl}: {@code {"results":[{"metadata":
   * {"title":...},"markdown":{"raw_markdown":...} | "markdown":"..."}]}}. */
  public static Extraction.Outcome viaCrawl4aiRemote(String url) {
    String apiUrl = System.getenv("CRAWL4AI_API_URL");
    if (apiUrl == null || apiUrl.isBlank()) {
      return new Extraction.Outcome.PermanentFailure(
          "Crawl4AI's local mode is browser automation (a bundled Chromium), an ML/browser "
              + "runtime this port does not embed. Set CRAWL4AI_API_URL to a remote/self-hosted "
              + "Crawl4AI server to use this engine.");
    }

    ObjectNode body = MAPPER.createObjectNode();
    body.putArray("urls").add(url);
    body.put("priority", 10);

    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(apiUrl.replaceAll("/$", "") + "/crawl"))
              .timeout(Duration.ofSeconds(60))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        return Extraction.unreachable("Crawl4AI HTTP " + response.statusCode());
      }
      JsonNode results = MAPPER.readTree(response.body()).path("results");
      if (!results.isArray() || results.isEmpty()) {
        return new Extraction.Outcome.PermanentFailure(
            "Could not extract any text content from this source. The content may be empty, "
                + "inaccessible, or in an unsupported format.");
      }
      JsonNode result = results.get(0);
      String title = result.path("metadata").path("title").asText(null);
      JsonNode markdownNode = result.path("markdown");
      String content =
          markdownNode.isObject() ? markdownNode.path("raw_markdown").asText(null) : markdownNode.asText(null);
      if (content == null || content.isBlank()) {
        return new Extraction.Outcome.PermanentFailure(
            "Could not extract any text content from this source. The content may be empty, "
                + "inaccessible, or in an unsupported format.");
      }
      return new Extraction.Outcome.Success(title, content);
    } catch (Exception e) {
      return Extraction.unreachable(e.getClass().getSimpleName());
    }
  }
}
