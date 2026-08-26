package io.akka.opennotebook.domain;

import java.time.Instant;

/** A saved prompt applied to a source's text to produce an insight (SPEC-001 §Transformations). */
public record Transformation(
    String id,
    String name,
    String title,
    String description,
    String prompt,
    boolean applyDefault,
    String modelId,
    Instant createdAt,
    boolean exists) {

  public static Transformation empty() {
    return new Transformation(null, null, null, null, null, false, null, null, false);
  }

  public static Transformation create(
      String id,
      String name,
      String title,
      String description,
      String prompt,
      boolean applyDefault,
      String modelId,
      Instant at) {
    return new Transformation(id, name, title, description, prompt, applyDefault, modelId, at, true);
  }
}
