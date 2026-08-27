package io.akka.opennotebook.bench;

import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.opennotebook.api.NoteEndpoint;
import io.akka.opennotebook.api.NotebookEndpoint;
import io.akka.opennotebook.api.SourceEndpoint;
import io.akka.opennotebook.domain.DeletePreview;
import io.akka.opennotebook.domain.DeleteResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Same three workloads as {@code bench/run-source.py}, driven over this port's own HTTP surface
 * (matching the original's own comparison method — both sides measured end to end, not against
 * an isolated decision neither system exposes that way). Writes {@code target/bench-java.json};
 * {@code bench/compare.py} reads it alongside {@code bench/answers-source.json}.
 *
 * <p>Run with {@code mvn -q -o test -Dtest=BenchmarkRunnerTest}.
 */
class BenchmarkRunnerTest extends TestKitSupport {

  @Test
  void run() throws IOException {
    var mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();

    root.set("title-preservation", titlePreservation(mapper));
    root.set("permanent-failure", permanentFailure(mapper));
    root.set("notebook-cascade-delete", notebookCascadeDelete(mapper));
    root.set("url-source", urlSource(mapper));
    root.set("timing-text-source-end-to-end", timingTextSourceEndToEnd(mapper));

    Path out = Path.of("target/bench-java.json");
    Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
  }

  private String newNotebook(String name) {
    return httpClient
        .POST("/notebooks")
        .withRequestBody(new NotebookEndpoint.CreateRequest(name, "bench"))
        .responseBodyAs(NotebookEndpoint.NotebookResponse.class)
        .invoke()
        .body()
        .notebookId();
  }

  private SourceEndpoint.SourceResponse waitForSettled(String sourceId) {
    var box = new SourceEndpoint.SourceResponse[1];
    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(15))
        .until(
            () -> {
              var s =
                  httpClient
                      .GET("/sources/" + sourceId)
                      .responseBodyAs(SourceEndpoint.SourceResponse.class)
                      .invoke()
                      .body();
              box[0] = s;
              return s.status().equals("COMPLETED") || s.status().equals("FAILED");
            });
    return box[0];
  }

  private ObjectNode titlePreservation(ObjectMapper mapper) {
    String nb = newNotebook("bench-title");
    var withTitle =
        httpClient
            .POST("/sources")
            .withRequestBody(
                new SourceEndpoint.CreateRequest(
                    "text", "The quick brown fox.", null, "caller title", List.of(nb)))
            .responseBodyAs(SourceEndpoint.SourceResponse.class)
            .invoke()
            .body();
    var settledWith = waitForSettled(withTitle.sourceId());

    var withoutTitle =
        httpClient
            .POST("/sources")
            .withRequestBody(
                new SourceEndpoint.CreateRequest(
                    "text", "The quick brown fox.", null, null, List.of(nb)))
            .responseBodyAs(SourceEndpoint.SourceResponse.class)
            .invoke()
            .body();
    var settledWithout = waitForSettled(withoutTitle.sourceId());

    ObjectNode n = mapper.createObjectNode();
    n.put("callerTitleSurvived", settledWith.title().equals("caller title"));
    n.put("placeholderOverwritten", !settledWithout.title().equals("Processing..."));
    n.put("fullTextVerbatim", settledWith.fullText().equals("The quick brown fox."));
    return n;
  }

  private ObjectNode permanentFailure(ObjectMapper mapper) {
    var emptyResponse =
        httpClient
            .POST("/sources")
            .withRequestBody(new SourceEndpoint.CreateRequest("text", "", null, null, List.of()))
            .invoke();

    String nb = newNotebook("bench-fail");
    var nonEmpty =
        httpClient
            .POST("/sources")
            .withRequestBody(
                new SourceEndpoint.CreateRequest("text", "not empty", null, null, List.of(nb)))
            .responseBodyAs(SourceEndpoint.SourceResponse.class)
            .invoke()
            .body();
    var settled = waitForSettled(nonEmpty.sourceId());

    ObjectNode n = mapper.createObjectNode();
    n.put("emptyTextRejectedBeforeSourceExists", emptyResponse.status().intValue() == 400);
    n.put("nonEmptyTextCompletes", settled.status().equals("COMPLETED"));
    return n;
  }

  private ObjectNode notebookCascadeDelete(ObjectMapper mapper) {
    String nb1 = newNotebook("bench-cascade-1");
    String nb2 = newNotebook("bench-cascade-2");

    var exclusive =
        httpClient
            .POST("/sources")
            .withRequestBody(
                new SourceEndpoint.CreateRequest("text", "exclusive", null, null, List.of(nb1)))
            .responseBodyAs(SourceEndpoint.SourceResponse.class)
            .invoke()
            .body();
    waitForSettled(exclusive.sourceId());

    var shared =
        httpClient
            .POST("/sources")
            .withRequestBody(
                new SourceEndpoint.CreateRequest(
                    "text", "shared", null, null, List.of(nb1, nb2)))
            .responseBodyAs(SourceEndpoint.SourceResponse.class)
            .invoke()
            .body();
    waitForSettled(shared.sourceId());

    httpClient
        .POST("/notes")
        .withRequestBody(new NoteEndpoint.CreateRequest("n", "note content", nb1))
        .invoke();

    var preview =
        httpClient.GET("/notebooks/" + nb1 + "/delete-preview").responseBodyAs(DeletePreview.class).invoke().body();

    var deleted =
        httpClient
            .DELETE("/notebooks/" + nb1 + "?deleteExclusiveSources=true")
            .responseBodyAs(DeleteResult.class)
            .invoke()
            .body();

    var exclusiveAfter = httpClient.GET("/sources/" + exclusive.sourceId()).invoke();
    var sharedAfterResp =
        httpClient
            .GET("/sources/" + shared.sourceId())
            .responseBodyAs(SourceEndpoint.SourceResponse.class)
            .invoke();

    ObjectNode n = mapper.createObjectNode();
    n.put("previewNoteCount", preview.noteCount());
    n.put("previewExclusiveSourceCount", preview.exclusiveSourceCount());
    n.put("previewSharedSourceCount", preview.sharedSourceCount());
    n.put("deletedNotes", deleted.deletedNotes());
    n.put("deletedSources", deleted.deletedSources());
    n.put("unlinkedSources", deleted.unlinkedSources());
    n.put("exclusiveSourceGoneAfter", exclusiveAfter.status().intValue() == 404);
    n.put("sharedSourceSurvivesAfter", sharedAfterResp.status().intValue() == 200);
    n.put(
        "sharedSourceKeepsOtherNotebook",
        sharedAfterResp.status().intValue() == 200
            && sharedAfterResp.body().notebookIds().equals(List.of(nb2)));
    return n;
  }

  private ObjectNode urlSource(ObjectMapper mapper) {
    String nb = newNotebook("bench-url");
    var source =
        httpClient
            .POST("/sources")
            .withRequestBody(
                new SourceEndpoint.CreateRequest(
                    "link", null, "https://example.com", null, List.of(nb)))
            .responseBodyAs(SourceEndpoint.SourceResponse.class)
            .invoke()
            .body();
    var settled = waitForSettled(source.sourceId());

    ObjectNode n = mapper.createObjectNode();
    n.put("title", settled.title());
    n.put(
        "fullTextContainsExpectedPhrase",
        settled.fullText() != null && settled.fullText().contains("domain is for use in"));
    n.put("completed", settled.status().equals("COMPLETED"));
    return n;
  }

  private ObjectNode timingTextSourceEndToEnd(ObjectMapper mapper) {
    String nb = newNotebook("bench-timing");
    int repeats = 20;
    List<Double> times = new ArrayList<>();
    for (int i = 0; i < repeats; i++) {
      long t0 = System.nanoTime();
      var created =
          httpClient
              .POST("/sources")
              .withRequestBody(
                  new SourceEndpoint.CreateRequest(
                      "text", "timing content", null, null, List.of(nb)))
              .responseBodyAs(SourceEndpoint.SourceResponse.class)
              .invoke()
              .body();
      waitForSettled(created.sourceId());
      long t1 = System.nanoTime();
      times.add((t1 - t0) / 1_000_000_000.0);
    }
    times.sort(Double::compareTo);

    ObjectNode n = mapper.createObjectNode();
    n.put("repeats", repeats);
    n.put("median_seconds", times.get(times.size() / 2));
    var all = mapper.createArrayNode();
    times.forEach(all::add);
    n.set("all_seconds", all);
    return n;
  }
}
