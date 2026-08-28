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
import io.akka.opennotebook.application.NotebookEntity;
import io.akka.opennotebook.application.NoteEntity;
import io.akka.opennotebook.application.SourceEntity;
import io.akka.opennotebook.domain.Insight;
import io.akka.opennotebook.domain.Note;
import io.akka.opennotebook.domain.NoteType;
import io.akka.opennotebook.domain.Source;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The frontend's {@code insights.ts} global routes ({@code api/routers/insights.py}) --
 * addressing an insight by its own id rather than by its owning source and a list index, the way
 * {@code ApiSourceEndpoint}'s {@code /{sourceId}/insights} nested routes already do.
 *
 * <p>The id an insight is addressed by here is {@code "<sourceId>:<insight.id()>"} (see {@link
 * Insight}'s class doc for why) -- an opaque token from the caller's point of view, the same as
 * every other id this port hands out. */
@HttpEndpoint("/api/insights")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiInsightEndpoint extends AbstractHttpEndpoint {

  public record InsightResponse(String id, String source_id, String insight_type, String content) {}

  public record SaveAsNoteRequest(String notebook_id) {}

  public record NoteResponse(String id, String title, String content, String note_type) {}

  private final ComponentClient componentClient;

  public ApiInsightEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  private record Resolved(String sourceId, Source source, Insight insight) {}

  private Resolved resolve(String compositeId) {
    int sep = compositeId.indexOf(':');
    if (sep < 0) return null;
    String sourceId = compositeId.substring(0, sep);
    String insightId = compositeId.substring(sep + 1);
    Source source;
    try {
      source = componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
    return source.insights().stream()
        .filter(i -> i.id().equals(insightId))
        .findFirst()
        .map(i -> new Resolved(sourceId, source, i))
        .orElse(null);
  }

  @Get("/{insightId}")
  public HttpResponse get(String insightId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Resolved resolved = resolve(insightId);
    if (resolved == null) return HttpResponses.notFound("Insight not found");
    return HttpResponses.ok(
        new InsightResponse(insightId, resolved.sourceId(), resolved.insight().insightType(), resolved.insight().content()));
  }

  @Delete("/{insightId}")
  public HttpResponse delete(String insightId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Resolved resolved = resolve(insightId);
    if (resolved == null) return HttpResponses.notFound("Insight not found");
    componentClient
        .forEventSourcedEntity(resolved.sourceId())
        .method(SourceEntity::removeInsightById)
        .invoke(new SourceEntity.RemoveInsightById(resolved.insight().id(), Instant.now()));
    return HttpResponses.ok();
  }

  /** R14: turn a source's insight into an AI note, linked to the caller's notebook. */
  @Post("/{insightId}/save-as-note")
  public HttpResponse saveAsNote(String insightId, SaveAsNoteRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Resolved resolved = resolve(insightId);
    if (resolved == null) return HttpResponses.notFound("Insight not found");

    String noteId = UUID.randomUUID().toString();
    Note note = Note.fromInsight(noteId, resolved.source().title(), resolved.insight(), request.notebook_id(), Instant.now());

    componentClient
        .forEventSourcedEntity(noteId)
        .method(NoteEntity::create)
        .invoke(new NoteEntity.Create(note.title(), note.content(), NoteType.AI, List.of(request.notebook_id()), Instant.now()));
    componentClient
        .forEventSourcedEntity(request.notebook_id())
        .method(NotebookEntity::linkNote)
        .invoke(new NotebookEntity.NoteLinked(noteId, Instant.now()));

    var created = componentClient.forEventSourcedEntity(noteId).method(NoteEntity::get).invoke();
    return HttpResponses.ok(new NoteResponse(created.noteId(), created.title(), created.content(), created.noteType().name()));
  }
}
