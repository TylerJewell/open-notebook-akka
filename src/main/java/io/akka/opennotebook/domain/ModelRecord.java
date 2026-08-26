package io.akka.opennotebook.domain;

import java.time.Instant;

/**
 * A named, provisioned AI model (SPEC-001 §Models) — a provider plus a model name, optionally
 * linked to a {@link Credential} for authentication. Named {@code ModelRecord} rather than
 * {@code Model} to avoid colliding with Akka SDK's own model-generation types.
 */
public record ModelRecord(
    String id,
    String name,
    String provider,
    String type,
    String credentialId,
    Instant createdAt,
    boolean exists) {

  public static final String TYPE_LANGUAGE = "language";
  public static final String TYPE_EMBEDDING = "embedding";
  public static final String TYPE_TEXT_TO_SPEECH = "text_to_speech";
  public static final String TYPE_SPEECH_TO_TEXT = "speech_to_text";

  public static ModelRecord empty() {
    return new ModelRecord(null, null, null, null, null, null, false);
  }

  public static ModelRecord create(
      String id, String name, String provider, String type, String credentialId, Instant at) {
    return new ModelRecord(id, name, provider, type, credentialId, at, true);
  }
}
