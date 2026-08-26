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
import io.akka.opennotebook.application.DefaultModelsEntity;
import io.akka.opennotebook.application.ModelEntity;
import io.akka.opennotebook.application.ModelsView;
import io.akka.opennotebook.domain.DefaultModels;
import io.akka.opennotebook.domain.ModelRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Provisioned models, and the server-wide default-per-purpose choice (SPEC-001 §Models). */
@HttpEndpoint("")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ModelEndpoint extends AbstractHttpEndpoint {

  public record CreateModelRequest(String name, String provider, String type, String credentialId) {}

  public record ModelResponse(String id, String name, String provider, String type, String credentialId) {}

  private final ComponentClient componentClient;

  public ModelEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/models")
  public HttpResponse create(CreateModelRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String id = UUID.randomUUID().toString();
    try {
      ModelRecord created =
          componentClient
              .forKeyValueEntity(id)
              .method(ModelEntity::create)
              .invoke(
                  new ModelEntity.Create(
                      request.name(), request.provider(), request.type(), request.credentialId(), Instant.now()));
      return HttpResponses.created(toApi(created), "/models/" + id);
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Get("/models")
  public HttpResponse list() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String type = requestContext().queryParams().getString("type").orElse(null);
    ModelsView.Entries entries =
        (type == null || type.isBlank())
            ? componentClient.forView().method(ModelsView::all).invoke()
            : componentClient.forView().method(ModelsView::byType).invoke(type);
    return HttpResponses.ok(
        entries.items().stream()
            .map(e -> new ModelResponse(e.id(), e.name(), e.provider(), e.type(), e.credentialId()))
            .toList());
  }

  @Get("/models/{modelId}")
  public HttpResponse get(String modelId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      ModelRecord model = componentClient.forKeyValueEntity(modelId).method(ModelEntity::get).invoke();
      return HttpResponses.ok(toApi(model));
    } catch (Exception e) {
      return HttpResponses.notFound("Model not found");
    }
  }

  @Delete("/models/{modelId}")
  public HttpResponse delete(String modelId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forKeyValueEntity(modelId).method(ModelEntity::delete).invoke();
      return HttpResponses.ok("deleted");
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Get("/settings/default-models")
  public HttpResponse getDefaults() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    DefaultModels defaults =
        componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::get).invoke();
    return HttpResponses.ok(defaults);
  }

  @Put("/settings/default-models")
  public HttpResponse setDefaults(DefaultModels request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    DefaultModels updated =
        componentClient
            .forKeyValueEntity(DefaultModelsEntity.ID)
            .method(DefaultModelsEntity::set)
            .invoke(request);
    return HttpResponses.ok(updated);
  }

  private ModelResponse toApi(ModelRecord m) {
    return new ModelResponse(m.id(), m.name(), m.provider(), m.type(), m.credentialId());
  }
}
