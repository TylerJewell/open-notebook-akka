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
import io.akka.opennotebook.ai.AiClient;
import io.akka.opennotebook.application.DefaultModelsEntity;
import io.akka.opennotebook.application.DefaultPromptEntity;
import io.akka.opennotebook.application.TransformationEntity;
import io.akka.opennotebook.application.TransformationsView;
import io.akka.opennotebook.domain.DefaultModels;
import io.akka.opennotebook.domain.Transformation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The frontend's {@code frontend/src/lib/api/transformations.ts} against {@link
 * TransformationEntity} / {@link TransformationsView} -- snake_case wire shape, plus {@code
 * execute} (SPEC-001 SS7's model call, run directly against caller-supplied text rather than a
 * saved source) and the default-prompt setting neither the bare-path {@code TransformationEndpoint}
 * nor {@code SourceEndpoint} ever needed. */
@HttpEndpoint("/api/transformations")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiTransformationEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(
      String name, String title, String description, String prompt, boolean apply_default, String model_id) {}

  public record TransformationResponse(
      String id, String name, String title, String description, String prompt, boolean apply_default,
      String model_id, Instant created) {}

  public record ExecuteRequest(String transformation_id, String input_text, String model_id) {}

  public record ExecuteResponse(String output) {}

  public record DefaultPromptResponse(String transformation_instructions) {}

  private final ComponentClient componentClient;
  private final AiClient aiClient;

  public ApiTransformationEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
    this.aiClient = new AiClient(componentClient);
  }

  @Post("")
  public HttpResponse create(CreateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String id = UUID.randomUUID().toString();
    try {
      Transformation created =
          componentClient
              .forKeyValueEntity(id)
              .method(TransformationEntity::create)
              .invoke(
                  new TransformationEntity.Create(
                      request.name(), request.title(), request.description(), request.prompt(),
                      request.apply_default(), request.model_id(), Instant.now()));
      return HttpResponses.created(toApi(created), "/api/transformations/" + id);
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Get("")
  public HttpResponse list() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    TransformationsView.Entries entries = componentClient.forView().method(TransformationsView::all).invoke();
    // Every list row is resolved to a full record so apply_default's type doesn't flip between
    // int (list) and boolean (single item) the way the bare-path SS 6 cross-cutting note flags.
    return HttpResponses.ok(entries.items().stream().map(e -> toApi(fetch(e.id()))).filter(java.util.Objects::nonNull).toList());
  }

  @Get("/default-prompt")
  public HttpResponse getDefaultPrompt() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String prompt = componentClient.forKeyValueEntity(DefaultPromptEntity.ID).method(DefaultPromptEntity::get).invoke();
    return HttpResponses.ok(new DefaultPromptResponse(prompt));
  }

  @Put("/default-prompt")
  public HttpResponse setDefaultPrompt(DefaultPromptResponse request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String prompt =
        componentClient
            .forKeyValueEntity(DefaultPromptEntity.ID)
            .method(DefaultPromptEntity::set)
            .invoke(request.transformation_instructions());
    return HttpResponses.ok(new DefaultPromptResponse(prompt));
  }

  @Post("/execute")
  public HttpResponse execute(ExecuteRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    if (request.input_text() == null || request.input_text().isBlank()) {
      return HttpResponses.badRequest("input_text cannot be empty");
    }
    Transformation transformation = fetch(request.transformation_id());
    if (transformation == null) return HttpResponses.badRequest("Transformation not found");
    String modelId = request.model_id() != null ? request.model_id() : transformation.modelId();
    if (modelId == null) {
      DefaultModels defaults =
          componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::get).invoke();
      modelId = defaults.defaultTransformationModel();
    }
    try {
      String output =
          aiClient.chatComplete(modelId, transformation.prompt(), List.of(new AiClient.ChatMessage("user", request.input_text())));
      return HttpResponses.ok(new ExecuteResponse(output));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Get("/{transformationId}")
  public HttpResponse get(String transformationId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Transformation t = fetch(transformationId);
    if (t == null) return HttpResponses.notFound("Transformation not found");
    return HttpResponses.ok(toApi(t));
  }

  @Put("/{transformationId}")
  public HttpResponse update(String transformationId, CreateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    // The entity has no dedicated update command (bare-path never needed one); a transformation
    // is few enough fields, and cheap enough to recreate wholesale, that delete-then-recreate at
    // the same id reaches the same observable state as a real in-place update would.
    try {
      componentClient.forKeyValueEntity(transformationId).method(TransformationEntity::delete).invoke();
    } catch (Exception ignored) {
      // Nothing to delete yet -- fine, this is effectively a create-if-absent.
    }
    try {
      Transformation updated =
          componentClient
              .forKeyValueEntity(transformationId)
              .method(TransformationEntity::create)
              .invoke(
                  new TransformationEntity.Create(
                      request.name(), request.title(), request.description(), request.prompt(),
                      request.apply_default(), request.model_id(), Instant.now()));
      return HttpResponses.ok(toApi(updated));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Delete("/{transformationId}")
  public HttpResponse delete(String transformationId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forKeyValueEntity(transformationId).method(TransformationEntity::delete).invoke();
      return HttpResponses.ok();
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  private Transformation fetch(String id) {
    try {
      return componentClient.forKeyValueEntity(id).method(TransformationEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }

  private TransformationResponse toApi(Transformation t) {
    return new TransformationResponse(
        t.id(), t.name(), t.title(), t.description(), t.prompt(), t.applyDefault(), t.modelId(), t.createdAt());
  }
}
