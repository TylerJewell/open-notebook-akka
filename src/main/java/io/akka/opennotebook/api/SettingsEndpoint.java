package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.opennotebook.application.ContentSettingsEntity;
import io.akka.opennotebook.domain.ContentSettings;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Content-processing settings (§Settings), extraction-runtime capabilities (§Capabilities), and
 * the language picker (§Languages). {@code CapabilitiesEndpoint} always reports the opt-in heavy
 * engines (Docling, Crawl4AI) as unavailable — true in this port's environment the same way the
 * source's own probe-based endpoint would report false if those runtimes were never installed;
 * see SPEC-001 for why those engines themselves are out of scope. Languages are derived from the
 * JVM's own {@link Locale} data rather than the source's CLDR (via {@code babel}/{@code
 * pycountry}) — the same capability (BCP-47 code plus a display name), a different underlying
 * locale database, so exact code coverage and display-name wording can differ.
 */
@HttpEndpoint("")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class SettingsEndpoint extends AbstractHttpEndpoint {

  public record CapabilitiesResponse(
      boolean doclingAvailable, boolean crawl4aiAvailable, boolean crawl4aiRemoteConfigured) {}

  public record LanguageResponse(String code, String name) {}

  private final ComponentClient componentClient;

  public SettingsEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/settings")
  public HttpResponse getSettings() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    ContentSettings settings =
        componentClient.forKeyValueEntity(ContentSettingsEntity.ID).method(ContentSettingsEntity::get).invoke();
    return HttpResponses.ok(settings);
  }

  @Put("/settings")
  public HttpResponse updateSettings(ContentSettings request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    ContentSettings updated =
        componentClient.forKeyValueEntity(ContentSettingsEntity.ID).method(ContentSettingsEntity::set).invoke(request);
    return HttpResponses.ok(updated);
  }

  @Get("/capabilities")
  public HttpResponse getCapabilities() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(new CapabilitiesResponse(false, false, false));
  }

  @Get("/languages")
  public HttpResponse listLanguages() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    TreeMap<String, String> byCode = new TreeMap<>();
    for (Locale locale : Locale.getAvailableLocales()) {
      if (locale.getLanguage().isEmpty()) continue;
      String code =
          locale.getCountry().isEmpty() ? locale.getLanguage() : locale.getLanguage() + "-" + locale.getCountry();
      byCode.putIfAbsent(code, locale.getDisplayName(Locale.ENGLISH));
    }
    List<LanguageResponse> languages =
        byCode.entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByValue())
            .map(e -> new LanguageResponse(e.getKey(), e.getValue()))
            .toList();
    return HttpResponses.ok(languages);
  }
}
