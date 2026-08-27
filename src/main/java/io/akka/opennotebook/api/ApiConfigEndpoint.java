package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import java.util.List;

/**
 * RENDERING.md R3/R4 data-layer adapter: the two bootstrap calls the vendored frontend makes
 * before it will render anything at all. {@code frontend/src/lib/config.ts}'s {@code
 * fetchConfig()} blocks the whole UI behind a connection-guard error screen until {@code GET
 * /api/config} answers; {@code auth-store.ts} calls {@code GET /api/auth/status} to decide
 * whether to show the password gate. Neither has a bare-path equivalent -- the original's version
 * string and this port's shared-password toggle were never previously exposed over HTTP.
 *
 * <p>Every record in the {@code io.akka.opennotebook.api} adapter classes (the ones with an
 * {@code Api} prefix, routed under {@code /api/...}) names its fields in the wire's own
 * snake_case rather than Java's usual camelCase, deliberately: Jackson serializes a record
 * component under its exact declared name with no naming strategy configured anywhere in this
 * project (no {@code @JsonNaming}, no custom {@code ObjectMapper}), and the frontend's data layer
 * ({@code frontend/src/lib/api/*}) reads snake_case exclusively. Renaming the component is
 * simpler and lower-risk than adding a project-wide Jackson naming strategy that would also have
 * to leave every already-tested bare-path endpoint's camelCase shape alone.
 */
@HttpEndpoint("/api")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiConfigEndpoint extends AbstractHttpEndpoint {

  public record ConfigResponse(String version) {}

  public record AuthStatusResponse(boolean auth_enabled) {}

  public record RecentlyViewedResponse(String type, String id, String title, String last_viewed_at) {}

  public record ProviderInfo(String name, String display_name, List<String> modalities, boolean env_configured) {}

  /**
   * The source's providers list (`api/routers/providers.py`) enumerates Esperanto's built-in
   * catalog. AiClient (SPEC-001 S6 D-7) normalizes providers to one OpenAI-compatible HTTP shape
   * rather than one SDK per provider, so this lists the same well-known names AiClient's own
   * DEFAULT_BASE_URL table already special-cases, plus "custom" for any OpenAI-compatible
   * endpoint reached purely via a credential's own baseUrl. env_configured is always false: this
   * port provisions every credential through the Credentials UI, never through server env vars.
   */
  private static final List<ProviderInfo> PROVIDERS =
      List.of(
          new ProviderInfo("openai", "OpenAI", List.of("language", "embedding", "text_to_speech", "speech_to_text"), false),
          new ProviderInfo("groq", "Groq", List.of("language"), false),
          new ProviderInfo("deepseek", "DeepSeek", List.of("language"), false),
          new ProviderInfo("together", "Together AI", List.of("language", "embedding"), false),
          new ProviderInfo("openrouter", "OpenRouter", List.of("language"), false),
          new ProviderInfo("mistral", "Mistral", List.of("language", "embedding"), false),
          new ProviderInfo("custom", "Custom (OpenAI-compatible)", List.of("language", "embedding", "text_to_speech", "speech_to_text"), false));

  @Get("/config")
  public HttpResponse config() {
    return HttpResponses.ok(new ConfigResponse("open-notebook-akka"));
  }

  @Get("/auth/status")
  public HttpResponse authStatus() {
    String password = System.getenv("OPEN_NOTEBOOK_PASSWORD");
    return HttpResponses.ok(new AuthStatusResponse(password != null && !password.isBlank()));
  }

  /**
   * The source tracks "recently viewed" notebooks/sources/notes in a dedicated table
   * ({@code open_notebook:recently_viewed}) updated on every read. This port has no equivalent
   * write path anywhere a GET happens -- adding one would mean every read-side entity/view call
   * also issuing a write, which no rule in SPEC-001 requires and which would make every "get"
   * request non-idempotent for a capability (browsing history) the port never claimed. Documented
   * narrowing: always empty, honestly, rather than fabricated from creation order.
   */
  @Get("/recently-viewed")
  public HttpResponse recentlyViewed() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(List.<RecentlyViewedResponse>of());
  }

  @Get("/providers")
  public HttpResponse providers() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(PROVIDERS);
  }
}
