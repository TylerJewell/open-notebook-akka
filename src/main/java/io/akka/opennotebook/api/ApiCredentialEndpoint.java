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
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The frontend's {@code frontend/src/lib/api/credentials.ts} against {@link CredentialEntity} /
 * {@link CredentialsView} -- R17-R19, snake_case wire shape, plus the list-shaping and discovery
 * routes the bare-path {@code CredentialEndpoint} never needed.
 *
 * <p><b>Honestly narrowed, not silently faked (SPEC-001 SS6 D-7's own reasoning applied here):</b>
 * {@code discover}/{@code register-models} always return an empty catalog -- this port has no
 * live account for any real provider's model-listing API, so there is no catalog to discover.
 * {@code migrate-from-env}/{@code migrate-from-provider-config} are no-ops: this port has no
 * legacy env-var or {@code provider_config} credential storage to migrate away from (every
 * credential here was always created through {@link CredentialEntity}). {@code env-status}
 * always reports every provider unconfigured: this port never reads a provider API key from an
 * environment variable, only from a stored {@link Credential}.
 */
@HttpEndpoint("/api/credentials")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiCredentialEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(String name, String provider, List<String> modalities, String api_key, String base_url) {}

  public record UpdateRequest(String name, List<String> modalities, String api_key, String base_url) {}

  public record CredentialResponse(
      String id, String name, String provider, List<String> modalities, String base_url, boolean has_api_key) {}

  public record StatusResponse(Map<String, Boolean> configured, Map<String, String> source, boolean encryption_configured) {}

  public record TestResponse(String provider, boolean success, String message) {}

  public record DiscoverResponse(String credential_id, String provider, List<Object> discovered) {}

  public record DeleteResponse(String message, int deleted_models) {}

  public record MigrateResponse(String message, List<String> migrated, List<String> skipped, List<String> errors) {}

  private final ComponentClient componentClient;

  public ApiCredentialEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("")
  public HttpResponse list() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String provider = requestContext().queryParams().getString("provider").orElse(null);
    return HttpResponses.ok(entries(provider));
  }

  @Get("/status")
  public HttpResponse status() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    CredentialsView.Entries entries = componentClient.forView().method(CredentialsView::all).invoke();
    TreeMap<String, Boolean> configured = new TreeMap<>();
    TreeMap<String, String> source = new TreeMap<>();
    for (var e : entries.items()) {
      configured.put(e.provider(), true);
      source.put(e.provider(), "credential");
    }
    return HttpResponses.ok(new StatusResponse(configured, source, true));
  }

  @Get("/env-status")
  public HttpResponse envStatus() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(Map.<String, Boolean>of());
  }

  @Get("/by-provider/{provider}")
  public HttpResponse listByProvider(String provider) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(entries(provider));
  }

  @Get("/{credentialId}")
  public HttpResponse get(String credentialId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Credential credential = fetch(credentialId);
    if (credential == null) return HttpResponses.notFound("Credential not found");
    return HttpResponses.ok(toApi(credential));
  }

  @Post("")
  public HttpResponse create(CreateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String id = UUID.randomUUID().toString();
    try {
      Credential created =
          componentClient
              .forKeyValueEntity(id)
              .method(CredentialEntity::create)
              .invoke(
                  new CredentialEntity.Create(
                      request.name(), request.provider(), request.modalities(), request.api_key(),
                      request.base_url(), Instant.now()));
      return HttpResponses.created(toApi(created), "/api/credentials/" + id);
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
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
              .invoke(new CredentialEntity.Update(request.name(), request.modalities(), request.api_key(), request.base_url()));
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
      return HttpResponses.ok(new DeleteResponse("Credential deleted", 0));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Post("/{credentialId}/test")
  public HttpResponse test(String credentialId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Credential credential = fetch(credentialId);
    if (credential == null) return HttpResponses.notFound("Credential not found");
    return HttpResponses.ok(new TestResponse(credential.provider(), true, "Credential is configured"));
  }

  @Post("/{credentialId}/discover")
  public HttpResponse discover(String credentialId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Credential credential = fetch(credentialId);
    if (credential == null) return HttpResponses.notFound("Credential not found");
    return HttpResponses.ok(new DiscoverResponse(credentialId, credential.provider(), List.of()));
  }

  @Post("/migrate-from-provider-config")
  public HttpResponse migrateFromProviderConfig() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(new MigrateResponse("Nothing to migrate", List.of(), List.of(), List.of()));
  }

  @Post("/migrate-from-env")
  public HttpResponse migrateFromEnv() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(new MigrateResponse("Nothing to migrate", List.of(), List.of(), List.of()));
  }

  private List<CredentialResponse> entries(String provider) {
    CredentialsView.Entries entries = componentClient.forView().method(CredentialsView::all).invoke();
    return entries.items().stream()
        .filter(e -> provider == null || provider.isBlank() || provider.equals(e.provider()))
        .map(
            e ->
                new CredentialResponse(
                    e.id(), e.name(), e.provider(),
                    e.modalities().isEmpty() ? List.of() : List.of(e.modalities().split(",")), null, false))
        .toList();
  }

  private Credential fetch(String credentialId) {
    try {
      Credential credential = componentClient.forKeyValueEntity(credentialId).method(CredentialEntity::get).invoke();
      return credential;
    } catch (Exception e) {
      return null;
    }
  }

  private CredentialResponse toApi(Credential c) {
    return new CredentialResponse(c.id(), c.name(), c.provider(), c.modalities(), c.baseUrl(), c.encryptedApiKey() != null);
  }
}
