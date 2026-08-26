package io.akka.opennotebook.domain;

import java.time.Instant;
import java.util.List;

/**
 * One provider account's authentication and endpoint configuration (SPEC-001 §Credentials).
 * {@code encryptedApiKey} is ciphertext at rest (see {@link io.akka.opennotebook.ai.EncryptionUtil});
 * no endpoint ever returns it decrypted.
 */
public record Credential(
    String id,
    String name,
    String provider,
    List<String> modalities,
    String encryptedApiKey,
    String baseUrl,
    Instant createdAt,
    boolean exists) {

  public static Credential empty() {
    return new Credential(null, null, null, List.of(), null, null, null, false);
  }

  public static Credential create(
      String id,
      String name,
      String provider,
      List<String> modalities,
      String encryptedApiKey,
      String baseUrl,
      Instant at) {
    return new Credential(id, name, provider, modalities, encryptedApiKey, baseUrl, at, true);
  }
}
