package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.JwtClaims;
import akka.javasdk.Principals;
import akka.javasdk.Tracing;
import akka.javasdk.http.QueryParams;
import akka.javasdk.http.RequestContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthGuardTest {

  private static RequestContext withAuthHeader(String value) {
    return new RequestContext() {
      public String selfRegion() {
        throw new UnsupportedOperationException();
      }

      public Principals getPrincipals() {
        throw new UnsupportedOperationException();
      }

      public JwtClaims getJwtClaims() {
        throw new UnsupportedOperationException();
      }

      public Optional<HttpHeader> requestHeader(String name) {
        return value == null ? Optional.empty() : Optional.of(HttpHeader.parse(name, value));
      }

      public List<HttpHeader> allRequestHeaders() {
        throw new UnsupportedOperationException();
      }

      public Tracing tracing() {
        throw new UnsupportedOperationException();
      }

      public QueryParams queryParams() {
        throw new UnsupportedOperationException();
      }

      public Optional<String> lastSeenSseEventId() {
        throw new UnsupportedOperationException();
      }
    };
  }

  @Test
  void disabledWhenNoPasswordConfigured() {
    assertThat(AuthGuard.check(withAuthHeader(null), null)).isNull();
    assertThat(AuthGuard.check(withAuthHeader(null), "")).isNull();
  }

  @Test
  void rejectsMissingHeaderWhenPasswordSet() {
    HttpResponse response = AuthGuard.check(withAuthHeader(null), "secret");
    assertThat(response.status()).isEqualTo(StatusCodes.UNAUTHORIZED);
  }

  @Test
  void rejectsWrongScheme() {
    HttpResponse response = AuthGuard.check(withAuthHeader("Basic secret"), "secret");
    assertThat(response.status()).isEqualTo(StatusCodes.UNAUTHORIZED);
  }

  @Test
  void rejectsWrongPassword() {
    HttpResponse response = AuthGuard.check(withAuthHeader("Bearer wrong"), "secret");
    assertThat(response.status()).isEqualTo(StatusCodes.UNAUTHORIZED);
  }

  @Test
  void acceptsCorrectBearerPassword() {
    assertThat(AuthGuard.check(withAuthHeader("Bearer secret"), "secret")).isNull();
  }
}
