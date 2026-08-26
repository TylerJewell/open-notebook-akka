package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.opennotebook.application.CredentialEntity;
import io.akka.opennotebook.application.CredentialsView;
import io.akka.opennotebook.domain.Credential;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A provider account's credentials (SPEC-001 §Credentials). Never returns {@code apiKey} —
 * matches the source's own rule (AGENTS.md: "NEVER return API key values from any endpoint").
 */
@HttpEndpoint("/credentials")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class CredentialEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(String name, String provider, List<String> modalities, String apiKey, String baseUrl) {}

  public record UpdateRequest(String name, List<String> modalities, String apiKey, String baseUrl) {}

  public record CredentialResponse(
      String id, String name, String provider, List<String> modalities, String baseUrl, boolean hasApiKey) {}

  private final ComponentClient componentClient;

  public CredentialEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("")
  public HttpResponse create(CreateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    if (request.name() == null || request.name().isBlank()) {
      return HttpResponses.badRequest("Credential name cannot be empty");
    }
    if (request.provider() == null || request.provider().isBlank()) {
      return HttpResponses.badRequest("Credential provider cannot be empty");
    }
    String id = UUID.randomUUID().toString();
    Credential created;
    try {
      created =
          componentClient
              .forKeyValueEntity(id)
              .method(CredentialEntity::create)
              .invoke(
                  new CredentialEntity.Create(
                      request.name(), request.provider(), request.modalities(), request.apiKey(),
                      request.baseUrl(), Instant.now()));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
    return HttpResponses.created(toApi(created), "/credentials/" + id);
  }

  @Get("")
  public HttpResponse list() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    CredentialsView.Entries entries =
        componentClient.forView().method(CredentialsView::all).invoke();
    List<CredentialResponse> items =
        entries.items().stream()
            .map(e -> new CredentialResponse(e.id(), e.name(), e.provider(),
                e.modalities().isEmpty() ? List.of() : List.of(e.modalities().split(",")), null, false))
            .toList();
    return HttpResponses.ok(items);
  }

  @Get("/{credentialId}")
  public HttpResponse get(String credentialId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Credential credential = fetch(credentialId);
    if (credential == null) {
      return HttpResponses.notFound("Credential not found");
    }
    return HttpResponses.ok(toApi(credential));
  }

  @Put("/{credentialId}")
  public HttpResponse update(String credentialId, UpdateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      Credential updated =
          componentClient
              .forKeyValueEntity(credentialId)
              .method(CredentialEntity::update)
              .invoke(new CredentialEntity.Update(request.name(), request.modalities(), request.apiKey(), request.baseUrl()));
      return HttpResponses.ok(toApi(updated));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Delete("/{credentialId}")
  public HttpResponse delete(String credentialId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forKeyValueEntity(credentialId).method(CredentialEntity::delete).invoke();
      return HttpResponses.ok("deleted");
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  private Credential fetch(String credentialId) {
    try {
      Credential credential =
          componentClient.forKeyValueEntity(credentialId).method(CredentialEntity::get).invoke();
      return credential;
    } catch (Exception e) {
      return null;
    }
  }

  private CredentialResponse toApi(Credential c) {
    return new CredentialResponse(
        c.id(), c.name(), c.provider(), c.modalities(), c.baseUrl(), c.encryptedApiKey() != null);
  }
}
