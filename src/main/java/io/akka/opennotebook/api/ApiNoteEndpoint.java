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
import io.akka.opennotebook.application.NotebookEntity;
import io.akka.opennotebook.application.NoteEntity;
import io.akka.opennotebook.application.NotesView;
import io.akka.opennotebook.domain.Note;
import io.akka.opennotebook.domain.NoteType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The frontend's {@code frontend/src/lib/api/notes.ts} against {@link NoteEntity} / {@link
 * NotesView} -- R13-R15, snake_case wire shape, plus the list, update and delete routes the
 * bare-path {@code NoteEndpoint} never needed. */
@HttpEndpoint("/api/notes")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiNoteEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(String title, String content, String note_type, String notebook_id) {}

  public record UpdateRequest(String title, String content) {}

  public record NoteResponse(
      String id, String title, String content, String note_type, Instant created, Instant updated) {}

  private final ComponentClient componentClient;

  public ApiNoteEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("")
  public HttpResponse list() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String notebookId = requestContext().queryParams().getString("notebook_id").orElse(null);
    NotesView.Entries entries =
        (notebookId == null || notebookId.isBlank())
            ? componentClient.forView().method(NotesView::all).invoke()
            : componentClient.forView().method(NotesView::byNotebook).invoke(notebookId);
    return HttpResponses.ok(
        entries.items().stream()
            .map(this::toApi)
            .sorted(java.util.Comparator.comparing(NoteResponse::updated).reversed())
            .toList());
  }

  @Get("/{noteId}")
  public HttpResponse get(String noteId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Note note = fetch(noteId);
    if (note == null) return HttpResponses.notFound("Note not found");
    return HttpResponses.ok(toApi(note));
  }

  @Post("")
  public HttpResponse create(CreateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    if (request.content() != null && request.content().isBlank()) {
      return HttpResponses.badRequest("Note content cannot be empty");
    }
    String noteId = UUID.randomUUID().toString();
    List<String> notebooks = request.notebook_id() != null ? List.of(request.notebook_id()) : List.of();
    NoteType type = "AI".equalsIgnoreCase(request.note_type()) ? NoteType.AI : NoteType.HUMAN;
    try {
      componentClient
          .forEventSourcedEntity(noteId)
          .method(NoteEntity::create)
          .invoke(new NoteEntity.Create(request.title(), request.content(), type, notebooks, Instant.now()));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
    for (String notebookId : notebooks) {
      componentClient
          .forEventSourcedEntity(notebookId)
          .method(NotebookEntity::linkNote)
          .invoke(new NotebookEntity.NoteLinked(noteId, Instant.now()));
    }
    return HttpResponses.created(toApi(fetch(noteId)), "/api/notes/" + noteId);
  }

  @Put("/{noteId}")
  public HttpResponse update(String noteId, UpdateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient
          .forEventSourcedEntity(noteId)
          .method(NoteEntity::update)
          .invoke(new NoteEntity.Update(request.title(), request.content(), Instant.now()));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
    return HttpResponses.ok(toApi(fetch(noteId)));
  }

  @Delete("/{noteId}")
  public HttpResponse delete(String noteId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forEventSourcedEntity(noteId).method(NoteEntity::delete).invoke(new NoteEntity.NoteDeleted(Instant.now()));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
    return HttpResponses.ok();
  }

  private Note fetch(String noteId) {
    try {
      return componentClient.forEventSourcedEntity(noteId).method(NoteEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }

  private NoteResponse toApi(Note n) {
    return new NoteResponse(n.noteId(), n.title(), n.content(), n.noteType().name().toLowerCase(), n.createdAt(), n.updatedAt());
  }

  private NoteResponse toApi(NotesView.Entry e) {
    return new NoteResponse(
        e.noteId(), e.title().orElse(null), e.content().orElse(null), e.noteType().toLowerCase(), e.createdAt(), e.updatedAt());
  }
}
