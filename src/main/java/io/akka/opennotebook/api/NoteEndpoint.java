package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import io.akka.opennotebook.application.NoteEntity;
import io.akka.opennotebook.application.NotebookEntity;
import io.akka.opennotebook.domain.Note;
import io.akka.opennotebook.domain.NoteType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@HttpEndpoint("/notes")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class NoteEndpoint {

  public record CreateRequest(String title, String content, String notebookId) {}

  public record NoteResponse(
      String noteId, String title, String content, String noteType, List<String> notebookIds) {}

  private final ComponentClient componentClient;

  public NoteEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("")
  public HttpResponse create(CreateRequest request) {
    if (request.notebookId() == null || request.notebookId().isBlank()) {
      return HttpResponses.badRequest("Notebook ID must be provided");
    }
    if (request.content() != null && request.content().isBlank()) {
      return HttpResponses.badRequest("Note content cannot be empty");
    }
    String noteId = UUID.randomUUID().toString();
    // R13: the note exists and is returned to the caller whether or not anything downstream
    // (e.g. an embedding step) ever runs.
    componentClient
        .forEventSourcedEntity(noteId)
        .method(NoteEntity::create)
        .invoke(
            new NoteEntity.Create(
                request.title(), request.content(), NoteType.HUMAN, List.of(request.notebookId()), Instant.now()));

    componentClient
        .forEventSourcedEntity(request.notebookId())
        .method(NotebookEntity::linkNote)
        .invoke(new NotebookEntity.NoteLinked(noteId, Instant.now()));

    return HttpResponses.created(toApi(fetch(noteId)), "/notes/" + noteId);
  }

  @Get("/{noteId}")
  public HttpResponse get(String noteId) {
    Note note = fetch(noteId);
    if (note == null) {
      return HttpResponses.notFound("Note not found");
    }
    return HttpResponses.ok(toApi(note));
  }

  private Note fetch(String noteId) {
    try {
      return componentClient.forEventSourcedEntity(noteId).method(NoteEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }

  private NoteResponse toApi(Note note) {
    return new NoteResponse(
        note.noteId(), note.title(), note.content(), note.noteType().name(), List.copyOf(note.notebookIds()));
  }
}
