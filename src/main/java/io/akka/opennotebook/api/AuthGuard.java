package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.http.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The single shared-password check the source applies to every API request (SPEC-001 §Auth,
 * {@code api/auth.py}'s {@code PasswordAuthMiddleware}): disabled entirely when
 * {@code OPEN_NOTEBOOK_PASSWORD} is unset, otherwise a constant-time check of a
 * {@code Authorization: Bearer <password>} header. Called at the top of every endpoint method
 * that mutates or reads server state — this SDK version has no request-level filter hook, so
 * each endpoint calls it explicitly rather than the source's single ASGI middleware.
 */
final class AuthGuard {

  private AuthGuard() {}

  static HttpResponse check(RequestContext requestContext) {
    return check(requestContext, System.getenv("OPEN_NOTEBOOK_PASSWORD"));
  }

  static HttpResponse check(RequestContext requestContext, String password) {
    if (password == null || password.isBlank()) {
      return null;
    }
    var authHeader = requestContext.requestHeader("Authorization");
    if (authHeader.isEmpty()) {
      return unauthorized("Missing authorization header");
    }
    String value = authHeader.get().value();
    int space = value.indexOf(' ');
    if (space < 0 || !"bearer".equalsIgnoreCase(value.substring(0, space))) {
      return unauthorized("Invalid authorization header format");
    }
    String credentials = value.substring(space + 1);
    if (!constantTimeEquals(credentials, password)) {
      return unauthorized("Invalid password");
    }
    return null;
  }

  private static HttpResponse unauthorized(String message) {
    return HttpResponse.create()
        .withStatus(StatusCodes.UNAUTHORIZED)
        .withEntity(akka.http.javadsl.model.ContentTypes.TEXT_PLAIN_UTF8, message);
  }

  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
