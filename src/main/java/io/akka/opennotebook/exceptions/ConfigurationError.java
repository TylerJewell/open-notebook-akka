package io.akka.opennotebook.exceptions;

/**
 * Raised when an AI capability is invoked without a usable model/credential configuration —
 * mirrors the source's {@code ConfigurationError} (maps to HTTP 422, per the source's own
 * exception-to-status convention in {@code api/main.py}).
 */
public class ConfigurationError extends RuntimeException {
  public ConfigurationError(String message) {
    super(message);
  }
}
