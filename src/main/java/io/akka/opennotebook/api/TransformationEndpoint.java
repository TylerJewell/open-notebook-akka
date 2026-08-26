package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.opennotebook.application.TransformationEntity;
import io.akka.opennotebook.application.TransformationsView;
import io.akka.opennotebook.domain.Transformation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Saved prompts a caller can run against a source's text (SPEC-001 §Transformations). */
@HttpEndpoint("/transformations")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class TransformationEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(
      String name, String title, String description, String prompt, boolean applyDefault, String modelId) {}

  private final ComponentClient componentClient;

  public TransformationEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
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
                      request.applyDefault(), request.modelId(), Instant.now()));
      return HttpResponses.created(created, "/transformations/" + id);
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Get("")
  public HttpResponse list() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    TransformationsView.Entries entries =
        componentClient.forView().method(TransformationsView::all).invoke();
    return HttpResponses.ok(entries.items());
  }

  @Get("/{transformationId}")
  public HttpResponse get(String transformationId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      Transformation t =
          componentClient.forKeyValueEntity(transformationId).method(TransformationEntity::get).invoke();
      return HttpResponses.ok(t);
    } catch (Exception e) {
      return HttpResponses.notFound("Transformation not found");
    }
  }

  @Delete("/{transformationId}")
  public HttpResponse delete(String transformationId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forKeyValueEntity(transformationId).method(TransformationEntity::delete).invoke();
      return HttpResponses.ok("deleted");
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }
}
