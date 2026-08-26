package io.akka.opennotebook.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Field-level encryption for credential API keys, mirroring the source's
 * {@code open_notebook/utils/encryption.py}: any string is accepted as
 * {@code OPEN_NOTEBOOK_ENCRYPTION_KEY} and a symmetric key is derived from it via SHA-256, so a
 * plain passphrase works without extra setup. AES/GCM stands in for the source's Fernet
 * (AES-CBC+HMAC) — both are authenticated symmetric encryption; the wire format differs but the
 * property this port's rules depend on (encrypted at rest, decrypted only server-side, never
 * returned by an endpoint) is the same.
 */
public final class EncryptionUtil {

  private static final String ENV_VAR = "OPEN_NOTEBOOK_ENCRYPTION_KEY";
  private static final int GCM_IV_LENGTH = 12;
  private static final int GCM_TAG_LENGTH = 128;

  private EncryptionUtil() {}

  private static SecretKeySpec deriveKey() {
    String raw = System.getenv(ENV_VAR);
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException(
          ENV_VAR + " is not set. Set this environment variable to any secret string to enable "
              + "encrypted storage of API keys.");
    }
    try {
      byte[] derived = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return new SecretKeySpec(derived, "AES");
    } catch (Exception e) {
      throw new IllegalStateException("Could not derive encryption key", e);
    }
  }

  public static String encrypt(String plaintext) {
    if (plaintext == null) return null;
    try {
      byte[] iv = new byte[GCM_IV_LENGTH];
      new SecureRandom().nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) {
      throw new IllegalStateException("Encryption failed", e);
    }
  }

  public static String decrypt(String encoded) {
    if (encoded == null) return null;
    try {
      byte[] combined = Base64.getDecoder().decode(encoded);
      byte[] iv = new byte[GCM_IV_LENGTH];
      System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
      byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
      System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Decryption failed: data appears to be encrypted but the key is incorrect, or "
              + EncryptionUtil.ENV_VAR + " changed since this value was stored.",
          e);
    }
  }
}
