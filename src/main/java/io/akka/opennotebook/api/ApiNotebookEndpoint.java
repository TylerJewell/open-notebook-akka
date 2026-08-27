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
import io.akka.opennotebook.application.NotebookDeletionWorkflow;
import io.akka.opennotebook.application.NotebookEntity;
import io.akka.opennotebook.application.NotebooksView;
import io.akka.opennotebook.application.SourceEntity;
import io.akka.opennotebook.domain.DeleteResult;
import io.akka.opennotebook.domain.Notebook;
import java.time.Instant;
import java.util.UUID;

/**
 * The frontend's {@code frontend/src/lib/api/notebooks.ts} against {@code NotebookEntity} /
 * {@link NotebooksView} / {@link NotebookDeletionWorkflow} -- same rules as the bare-path {@code
 * NotebookEndpoint}, snake_case wire shape, plus the list and per-notebook source-link routes the
 * bare-path surface never needed for a single-notebook-at-a-time slice.
 */
@HttpEndpoint("/api/notebooks")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiNotebookEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(String name, String description) {}

  public record UpdateRequest(String name, String description, Boolean archived) {}

  public record NotebookResponse(
      String id,
      String name,
      String description,
      boolean archived,
      Instant created,
      Instant updated,
      int source_count,
      int note_count) {}

  public record DeletePreviewResponse(
      String notebook_id, String notebook_name, int note_count, int exclusive_source_count, int shared_source_count) {}

  public record DeleteResponse(String message, int deleted_notes, int deleted_sources, int unlinked_sources) {}

  private final ComponentClient componentClient;

  public ApiNotebookEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("")
  public HttpResponse list() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String archived = requestContext().queryParams().getString("archived").orElse(null);
    NotebooksView.Entries entries =
        "true".equalsIgnoreCase(archived)
            ? componentClient.forView().method(NotebooksView::byArchived).invoke(true)
            : "false".equalsIgnoreCase(archived)
                ? componentClient.forView().method(NotebooksView::active).invoke()
                : componentClient.forView().method(NotebooksView::all).invoke();
    return HttpResponses.ok(
        entries.items().stream()
            .map(this::toApi)
            .sorted(java.util.Comparator.comparing(NotebookResponse::updated).reversed())
            .toList());
  }

  @Get("/{notebookId}")
  public HttpResponse get(String notebookId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Notebook notebook = fetch(notebookId);
    if (notebook == null) return HttpResponses.notFound("Notebook not found");
    return HttpResponses.ok(toApi(notebook));
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
    return HttpResponses.created(toApi(fetch(notebookId)), "/api/notebooks/" + notebookId);
  }

  @Put("/{notebookId}")
  public HttpResponse update(String notebookId, UpdateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient
          .forEventSourcedEntity(notebookId)
          .method(NotebookEntity::update)
          .invoke(new NotebookEntity.Update(request.name(), request.description(), request.archived(), Instant.now()));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
    return HttpResponses.ok(toApi(fetch(notebookId)));
  }

  @Get("/{notebookId}/delete-preview")
  public HttpResponse deletePreview(String notebookId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Notebook notebook = fetch(notebookId);
    if (notebook == null) return HttpResponses.notFound("Notebook not found");
    int exclusive = 0;
    int shared = 0;
    for (String sourceId : notebook.sourceIds()) {
      var source = componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
      if (source.isExclusiveTo(notebookId)) exclusive++;
      else shared++;
    }
    return HttpResponses.ok(
        new DeletePreviewResponse(notebookId, notebook.name(), notebook.noteIds().size(), exclusive, shared));
  }

  @Delete("/{notebookId}")
  public HttpResponse delete(String notebookId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Notebook notebook = fetch(notebookId);
    if (notebook == null) return HttpResponses.notFound("Notebook not found");
    boolean deleteExclusiveSources =
        Boolean.parseBoolean(requestContext().queryParams().getString("delete_exclusive_sources").orElse("false"));
    String workflowId = "delete-" + notebookId;
    componentClient
        .forWorkflow(workflowId)
        .method(NotebookDeletionWorkflow::start)
        .invoke(new NotebookDeletionWorkflow.Start(notebookId, deleteExclusiveSources));
    DeleteResult result = awaitResult(workflowId);
    return HttpResponses.ok(
        new DeleteResponse("Notebook deleted", result.deletedNotes(), result.deletedSources(), result.unlinkedSources()));
  }

  /** No bare-path equivalent: {@code SourceEndpoint.create}'s {@code notebooks} list is the only
   * previously-ported way to link a source to a notebook. The frontend also links/unlinks a
   * source from an already-open notebook view, independent of creation. */
  @Post("/{notebookId}/sources/{sourceId}")
  public HttpResponse addSource(String notebookId, String sourceId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Instant now = Instant.now();
    componentClient
        .forEventSourcedEntity(notebookId)
        .method(NotebookEntity::linkSource)
        .invoke(new NotebookEntity.SourceLinked(sourceId, now));
    componentClient
        .forEventSourcedEntity(sourceId)
        .method(SourceEntity::addToNotebook)
        .invoke(new SourceEntity.NotebookLinked(notebookId, now));
    return HttpResponses.ok();
  }

  @Delete("/{notebookId}/sources/{sourceId}")
  public HttpResponse removeSource(String notebookId, String sourceId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Instant now = Instant.now();
    componentClient
        .forEventSourcedEntity(notebookId)
        .method(NotebookEntity::unlinkSource)
        .invoke(new NotebookEntity.SourceUnlinked(sourceId, now));
    componentClient
        .forEventSourcedEntity(sourceId)
        .method(SourceEntity::removeFromNotebook)
        .invoke(new SourceEntity.NotebookUnlinked(notebookId, now));
    return HttpResponses.ok();
  }

  private DeleteResult awaitResult(String workflowId) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        return componentClient.forWorkflow(workflowId).method(NotebookDeletionWorkflow::result).invoke();
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

  private NotebookResponse toApi(Notebook n) {
    return new NotebookResponse(
        n.notebookId(), n.name(), n.description(), n.archived(), n.createdAt(), n.updatedAt(),
        n.sourceIds().size(), n.noteIds().size());
  }

  private NotebookResponse toApi(NotebooksView.Entry e) {
    return new NotebookResponse(
        e.notebookId(), e.name(), e.description().orElse(null), e.archived(), e.createdAt(), e.updatedAt(),
        e.sourceCount(), e.noteCount());
  }
}
