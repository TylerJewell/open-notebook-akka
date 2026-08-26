package io.akka.opennotebook.domain;

import java.util.List;

/** Server-wide content-processing preferences (SPEC-001 §Settings), mirroring the source's
 * singleton {@code content_settings} record. */
public record ContentSettings(
    String defaultContentProcessingEngineDoc,
    String defaultContentProcessingEngineUrl,
    String defaultEmbeddingOption,
    String autoDeleteFiles,
    boolean doclingOcr,
    boolean doclingFormulas,
    boolean doclingVision,
    List<String> youtubePreferredLanguages) {

  public static ContentSettings defaults() {
    return new ContentSettings("auto", "auto", "ask", "no", false, false, false, List.of("en"));
  }
}
