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
import io.akka.opennotebook.application.CredentialsView;
import io.akka.opennotebook.application.DefaultModelsEntity;
import io.akka.opennotebook.application.ModelEntity;
import io.akka.opennotebook.application.ModelsView;
import io.akka.opennotebook.domain.DefaultModels;
import io.akka.opennotebook.domain.ModelRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The frontend's {@code frontend/src/lib/api/models.ts} against {@link ModelEntity} / {@link
 * ModelsView} / {@link DefaultModelsEntity} -- same rules as the bare-path {@code ModelEndpoint},
 * snake_case wire shape, plus the shaping/discovery routes it never needed. {@code discover}/
 * {@code sync}/{@code auto-assign} carry the same honest-empty-catalog narrowing as {@link
 * ApiCredentialEndpoint}'s discover route: no live provider account, no catalog to discover from,
 * documented rather than faked.
 */
@HttpEndpoint("/api/models")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiModelEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(String name, String provider, String type, String credential_id) {}

  public record ModelResponse(String id, String name, String provider, String type, String credential_id) {}

  public record DefaultModelsResponse(
      String default_chat_model,
      String default_transformation_model,
      String large_context_model,
      String default_text_to_speech_model,
      String default_speech_to_text_model,
      String default_embedding_model,
      String default_tools_model) {

    static DefaultModelsResponse of(DefaultModels d) {
      return new DefaultModelsResponse(
          d.defaultChatModel(), d.defaultTransformationModel(), d.largeContextModel(),
          d.defaultTextToSpeechModel(), d.defaultSpeechToTextModel(), d.defaultEmbeddingModel(), d.defaultToolsModel());
    }

    DefaultModels toDomain() {
      return new DefaultModels(
          default_chat_model, default_transformation_model, large_context_model,
          default_text_to_speech_model, default_speech_to_text_model, default_embedding_model, default_tools_model);
    }
  }

  public record SyncResult(int created, int existing) {}

  public record CountResponse(String provider, int count) {}

  public record AutoAssignResult(Map<String, String> assigned) {}

  public record TestResult(String model_id, boolean success, String message) {}

  private final ComponentClient componentClient;

  public ApiModelEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("")
  public HttpResponse create(CreateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String id = UUID.randomUUID().toString();
    try {
      ModelRecord created =
          componentClient
              .forKeyValueEntity(id)
              .method(ModelEntity::create)
              .invoke(new ModelEntity.Create(request.name(), request.provider(), request.type(), request.credential_id(), Instant.now()));
      return HttpResponses.created(toApi(created), "/api/models/" + id);
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Get("")
  public HttpResponse list() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(all().stream().map(this::toApi).toList());
  }

  @Get("/defaults")
  public HttpResponse getDefaults() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    var defaults = componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::get).invoke();
    return HttpResponses.ok(DefaultModelsResponse.of(defaults));
  }

  @Put("/defaults")
  public HttpResponse updateDefaults(DefaultModelsResponse request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    var current = componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::get).invoke();
    var merged = mergePartial(current, request);
    var updated = componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::set).invoke(merged);
    return HttpResponses.ok(DefaultModelsResponse.of(updated));
  }

  /** The frontend sends a {@code Partial<ModelDefaults>} -- only the fields the caller changed --
   * so an omitted (null) field must keep its current value rather than being wiped, the same
   * partial-update contract {@link ApiCredentialEndpoint#update} already honors for a credential. */
  private static DefaultModels mergePartial(DefaultModels current, DefaultModelsResponse patch) {
    return new DefaultModels(
        orElse(patch.default_chat_model(), current.defaultChatModel()),
        orElse(patch.default_transformation_model(), current.defaultTransformationModel()),
        orElse(patch.large_context_model(), current.largeContextModel()),
        orElse(patch.default_text_to_speech_model(), current.defaultTextToSpeechModel()),
        orElse(patch.default_speech_to_text_model(), current.defaultSpeechToTextModel()),
        orElse(patch.default_embedding_model(), current.defaultEmbeddingModel()),
        orElse(patch.default_tools_model(), current.defaultToolsModel()));
  }

  @Get("/providers")
  public HttpResponse providerAvailability() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    CredentialsView.Entries credentials = componentClient.forView().method(CredentialsView::all).invoke();
    TreeMap<String, Boolean> availability = new TreeMap<>();
    for (var c : credentials.items()) availability.put(c.provider(), true);
    return HttpResponses.ok(availability);
  }

  @Get("/discover/{provider}")
  public HttpResponse discover(String provider) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(List.of());
  }

  @Post("/sync/{provider}")
  public HttpResponse syncProvider(String provider) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(new SyncResult(0, (int) all().stream().filter(m -> provider.equals(m.provider())).count()));
  }

  @Post("/sync")
  public HttpResponse syncAll() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(new SyncResult(0, all().size()));
  }

  @Get("/count/{provider}")
  public HttpResponse countByProvider(String provider) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(new CountResponse(provider, (int) all().stream().filter(m -> provider.equals(m.provider())).count()));
  }

  @Get("/by-provider/{provider}")
  public HttpResponse byProvider(String provider) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(all().stream().filter(m -> provider.equals(m.provider())).map(this::toApi).toList());
  }

  /** Assigns the first available model of each type to the matching default-model purpose that
   * is still unset -- the same "first configured wins" heuristic a person would apply by hand
   * through the Settings UI, run once instead of per click. */
  @Post("/auto-assign")
  public HttpResponse autoAssign() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    List<ModelsView.Entry> models = all();
    DefaultModels current =
        componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::get).invoke();
    String language = firstOfType(models, ModelRecord.TYPE_LANGUAGE);
    String embedding = firstOfType(models, ModelRecord.TYPE_EMBEDDING);
    String tts = firstOfType(models, ModelRecord.TYPE_TEXT_TO_SPEECH);
    String stt = firstOfType(models, ModelRecord.TYPE_SPEECH_TO_TEXT);
    DefaultModels updated =
        new DefaultModels(
            orElse(current.defaultChatModel(), language),
            orElse(current.defaultTransformationModel(), language),
            orElse(current.largeContextModel(), language),
            orElse(current.defaultTextToSpeechModel(), tts),
            orElse(current.defaultSpeechToTextModel(), stt),
            orElse(current.defaultEmbeddingModel(), embedding),
            orElse(current.defaultToolsModel(), language));
    componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::set).invoke(updated);
    Map<String, String> assigned = new TreeMap<>();
    if (updated.defaultChatModel() != null) assigned.put("default_chat_model", updated.defaultChatModel());
    if (updated.defaultEmbeddingModel() != null) assigned.put("default_embedding_model", updated.defaultEmbeddingModel());
    return HttpResponses.ok(new AutoAssignResult(assigned));
  }

  @Post("/{modelId}/test")
  public HttpResponse test(String modelId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forKeyValueEntity(modelId).method(ModelEntity::get).invoke();
    } catch (Exception e) {
      return HttpResponses.notFound("Model not found");
    }
    return HttpResponses.ok(new TestResult(modelId, true, "Model is configured"));
  }

  @Get("/{modelId}")
  public HttpResponse get(String modelId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      return HttpResponses.ok(toApi(componentClient.forKeyValueEntity(modelId).method(ModelEntity::get).invoke()));
    } catch (Exception e) {
      return HttpResponses.notFound("Model not found");
    }
  }

  @Delete("/{modelId}")
  public HttpResponse delete(String modelId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forKeyValueEntity(modelId).method(ModelEntity::delete).invoke();
      return HttpResponses.ok();
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  private static String orElse(String current, String fallback) {
    return (current == null || current.isBlank()) ? fallback : current;
  }

  private static String firstOfType(List<ModelsView.Entry> models, String type) {
    return models.stream().filter(m -> type.equals(m.type())).map(ModelsView.Entry::id).findFirst().orElse(null);
  }

  private List<ModelsView.Entry> all() {
    return componentClient.forView().method(ModelsView::all).invoke().items();
  }

  private ModelResponse toApi(ModelRecord m) {
    return new ModelResponse(m.id(), m.name(), m.provider(), m.type(), m.credentialId());
  }

  private ModelResponse toApi(ModelsView.Entry e) {
    return new ModelResponse(e.id(), e.name(), e.provider(), e.type(), e.credentialId());
  }
}
