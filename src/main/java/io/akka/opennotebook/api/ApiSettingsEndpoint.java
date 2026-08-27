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

/** The frontend's {@code frontend/src/lib/api/settings.ts}, {@code capabilities.ts} and {@code
 * podcasts.ts}'s {@code listLanguages()} against {@link ContentSettingsEntity} -- same rules and
 * divergences as the bare-path {@code SettingsEndpoint} (see its class doc), snake_case wire
 * shape. */
@HttpEndpoint("/api")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiSettingsEndpoint extends AbstractHttpEndpoint {

  public record SettingsResponse(
      String default_content_processing_engine_doc,
      String default_content_processing_engine_url,
      String default_embedding_option,
      String auto_delete_files,
      boolean docling_ocr,
      boolean docling_formulas,
      boolean docling_vision,
      List<String> youtube_preferred_languages) {

    static SettingsResponse of(ContentSettings s) {
      return new SettingsResponse(
          s.defaultContentProcessingEngineDoc(), s.defaultContentProcessingEngineUrl(), s.defaultEmbeddingOption(),
          s.autoDeleteFiles(), s.doclingOcr(), s.doclingFormulas(), s.doclingVision(), s.youtubePreferredLanguages());
    }

    ContentSettings toDomain() {
      return new ContentSettings(
          default_content_processing_engine_doc, default_content_processing_engine_url, default_embedding_option,
          auto_delete_files, docling_ocr, docling_formulas, docling_vision, youtube_preferred_languages);
    }
  }

  public record CapabilitiesResponse(boolean docling_available, boolean crawl4ai_available, boolean crawl4ai_remote_configured) {}

  public record LanguageResponse(String code, String name) {}

  private final ComponentClient componentClient;

  public ApiSettingsEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/settings")
  public HttpResponse getSettings() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    ContentSettings settings =
        componentClient.forKeyValueEntity(ContentSettingsEntity.ID).method(ContentSettingsEntity::get).invoke();
    return HttpResponses.ok(SettingsResponse.of(settings));
  }

  @Put("/settings")
  public HttpResponse updateSettings(SettingsResponse request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    ContentSettings current =
        componentClient.forKeyValueEntity(ContentSettingsEntity.ID).method(ContentSettingsEntity::get).invoke();
    ContentSettings merged =
        new ContentSettings(
            orElse(request.default_content_processing_engine_doc(), current.defaultContentProcessingEngineDoc()),
            orElse(request.default_content_processing_engine_url(), current.defaultContentProcessingEngineUrl()),
            orElse(request.default_embedding_option(), current.defaultEmbeddingOption()),
            orElse(request.auto_delete_files(), current.autoDeleteFiles()),
            request.docling_ocr(), request.docling_formulas(), request.docling_vision(),
            request.youtube_preferred_languages() != null ? request.youtube_preferred_languages() : current.youtubePreferredLanguages());
    ContentSettings updated =
        componentClient.forKeyValueEntity(ContentSettingsEntity.ID).method(ContentSettingsEntity::set).invoke(merged);
    return HttpResponses.ok(SettingsResponse.of(updated));
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
      String code = locale.getCountry().isEmpty() ? locale.getLanguage() : locale.getLanguage() + "-" + locale.getCountry();
      byCode.putIfAbsent(code, locale.getDisplayName(Locale.ENGLISH));
    }
    List<LanguageResponse> languages =
        byCode.entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByValue())
            .map(e -> new LanguageResponse(e.getKey(), e.getValue()))
            .toList();
    return HttpResponses.ok(languages);
  }

  private static String orElse(String value, String fallback) {
    return (value == null || value.isBlank()) ? fallback : value;
  }
}
