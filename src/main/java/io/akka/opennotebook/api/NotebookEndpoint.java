package io.akka.opennotebook.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.http.javadsl.model.HttpResponse;
import io.akka.opennotebook.application.NotebookDeletionWorkflow;
import io.akka.opennotebook.application.NotebookEntity;
import io.akka.opennotebook.application.SourceEntity;
import io.akka.opennotebook.domain.DeletePreview;
import io.akka.opennotebook.domain.DeleteResult;
import io.akka.opennotebook.domain.Notebook;
import java.time.Instant;
import java.util.UUID;

@HttpEndpoint("/notebooks")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class NotebookEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(String name, String description) {}

  public record NotebookResponse(
      String notebookId, String name, String description, int sourceCount, int noteCount) {}

  private final ComponentClient componentClient;

  public NotebookEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("")
  public HttpResponse create(CreateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    if (request.name() == null || request.name().isBlank()) {
      return HttpResponses.badRequest("Notebook name cannot be empty");
    }
    String notebookId = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(notebookId)
        .method(NotebookEntity::create)
        .invoke(new NotebookEntity.Create(request.name(), request.description(), Instant.now()));
    return HttpResponses.created(toApi(fetch(notebookId)), "/notebooks/" + notebookId);
  }

  @Get("/{notebookId}")
  public HttpResponse get(String notebookId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Notebook notebook = fetch(notebookId);
    if (notebook == null) {
      return HttpResponses.notFound("Notebook not found");
    }
    return HttpResponses.ok(toApi(notebook));
  }

  @Get("/{notebookId}/delete-preview")
  public HttpResponse deletePreview(String notebookId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Notebook notebook = fetch(notebookId);
    if (notebook == null) {
      return HttpResponses.notFound("Notebook not found");
    }
    int exclusive = 0;
    int shared = 0;
    for (String sourceId : notebook.sourceIds()) {
      var source =
          componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
      if (source.isExclusiveTo(notebookId)) {
        exclusive++;
      } else {
        shared++;
      }
    }
    return HttpResponses.ok(
        new DeletePreview(notebook.noteIds().size(), exclusive, shared));
  }

  @Delete("/{notebookId}")
  public HttpResponse delete(String notebookId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Notebook notebook = fetch(notebookId);
    if (notebook == null) {
      return HttpResponses.notFound("Notebook not found");
    }
    boolean deleteExclusiveSources =
        Boolean.parseBoolean(
            requestContext().queryParams().getString("deleteExclusiveSources").orElse("false"));
    String workflowId = "delete-" + notebookId;
    componentClient
        .forWorkflow(workflowId)
        .method(NotebookDeletionWorkflow::start)
        .invoke(new NotebookDeletionWorkflow.Start(notebookId, deleteExclusiveSources));

    DeleteResult result = awaitResult(workflowId);
    return HttpResponses.ok(result);
  }

  private DeleteResult awaitResult(String workflowId) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        return componentClient
            .forWorkflow(workflowId)
            .method(NotebookDeletionWorkflow::result)
            .invoke();
      } catch (Exception e) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    throw new IllegalStateException("Notebook deletion did not complete in time");
  }

  private Notebook fetch(String notebookId) {
    try {
      return componentClient.forEventSourcedEntity(notebookId).method(NotebookEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }

  private NotebookResponse toApi(Notebook notebook) {
    return new NotebookResponse(
        notebook.notebookId(),
        notebook.name(),
        notebook.description(),
        notebook.sourceIds().size(),
        notebook.noteIds().size());
  }
}
