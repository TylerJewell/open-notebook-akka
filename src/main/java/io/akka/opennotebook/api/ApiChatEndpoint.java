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
import io.akka.opennotebook.ai.NotebookContext;
import io.akka.opennotebook.application.ChatSessionEntity;
import io.akka.opennotebook.application.ChatSessionsView;
import io.akka.opennotebook.application.DefaultModelsEntity;
import io.akka.opennotebook.domain.ChatSession;
import io.akka.opennotebook.domain.DefaultModels;
import io.akka.opennotebook.domain.Notebook;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The frontend's {@code frontend/src/lib/api/chat.ts} (notebook-level chat) against {@link
 * ChatSessionEntity} / {@link ChatSessionsView} -- same rules and SS8 D-8 narrowing as the
 * bare-path {@code ChatEndpoint}, snake_case wire shape, plus the list/update/context routes it
 * never needed. Distinct from source-scoped chat, which {@link ApiSourceChatEndpoint} covers. */
@HttpEndpoint("/api/chat")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiChatEndpoint extends AbstractHttpEndpoint {

  public record CreateSessionRequest(String notebook_id, String title, String model_override) {}

  public record UpdateSessionRequest(String title, String model_override) {}

  public record SessionResponse(String id, String notebook_id, String title, Instant created, Instant updated) {}

  public record ChatMessageResponse(String id, String type, String content, Instant timestamp) {}

  public record SessionWithMessagesResponse(
      String id, String notebook_id, String title, List<ChatMessageResponse> messages) {}

  public record SendMessageRequest(String session_id, String message, Object context, String model_override) {}

  public record SendMessageResponse(String session_id, List<ChatMessageResponse> messages) {}

  public record BuildContextRequest(String notebook_id, Object context_config) {}

  public record ContextPayload(List<String> sources, List<String> notes) {}

  public record BuildContextResponse(ContextPayload context, int token_count, int char_count) {}

  private final ComponentClient componentClient;
  private final AiClient aiClient;

  public ApiChatEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
    this.aiClient = new AiClient(componentClient);
  }

  @Get("/sessions")
  public HttpResponse listSessions() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String notebookId = requestContext().queryParams().getString("notebook_id").orElse("");
    ChatSessionsView.Entries entries = componentClient.forView().method(ChatSessionsView::byNotebook).invoke(notebookId);
    return HttpResponses.ok(
        entries.items().stream()
            .map(e -> new SessionResponse(e.chatId(), e.notebookId(), null, e.createdAt(), e.updatedAt()))
            .toList());
  }

  @Post("/sessions")
  public HttpResponse createSession(CreateSessionRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String chatId = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(chatId)
        .method(ChatSessionEntity::create)
        .invoke(new ChatSessionEntity.Create(request.notebook_id(), Instant.now()));
    componentClient
        .forEventSourcedEntity(request.notebook_id())
        .method(io.akka.opennotebook.application.NotebookEntity::linkChatSession)
        .invoke(new io.akka.opennotebook.application.NotebookEntity.ChatSessionLinked(chatId, Instant.now()));
    ChatSession session = componentClient.forEventSourcedEntity(chatId).method(ChatSessionEntity::get).invoke();
    return HttpResponses.created(
        new SessionResponse(session.chatId(), session.notebookId(), request.title(), session.createdAt(), session.createdAt()),
        "/api/chat/sessions/" + chatId);
  }

  @Get("/sessions/{sessionId}")
  public HttpResponse getSession(String sessionId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    ChatSession session = fetch(sessionId);
    if (session == null) return HttpResponses.notFound("Chat session not found");
    return HttpResponses.ok(toDetail(session));
  }

  @Put("/sessions/{sessionId}")
  public HttpResponse updateSession(String sessionId, UpdateSessionRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    ChatSession session = fetch(sessionId);
    if (session == null) return HttpResponses.notFound("Chat session not found");
    // No dedicated rename command exists (a session's title is frontend-only presentation state
    // in this port; ChatSessionEntity has no title field to persist it into -- see class doc).
    return HttpResponses.ok(new SessionResponse(session.chatId(), session.notebookId(), request.title(), session.createdAt(), Instant.now()));
  }

  @Delete("/sessions/{sessionId}")
  public HttpResponse deleteSession(String sessionId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forEventSourcedEntity(sessionId).method(ChatSessionEntity::delete).invoke(new ChatSessionEntity.ChatDeleted(Instant.now()));
      return HttpResponses.ok();
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Post("/execute")
  public HttpResponse execute(SendMessageRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    if (request.message() == null || request.message().isBlank()) {
      return HttpResponses.badRequest("message cannot be empty");
    }
    ChatSession before = fetch(request.session_id());
    if (before == null) return HttpResponses.notFound("Chat session not found");

    componentClient
        .forEventSourcedEntity(request.session_id())
        .method(ChatSessionEntity::appendMessage)
        .invoke(new ChatSessionEntity.AppendMessage("user", request.message(), Instant.now()));

    String modelId = request.model_override() != null ? request.model_override() : defaultChatModel();
    Notebook notebook = NotebookContext.notebookOf(componentClient, before.notebookId());
    String context = NotebookContext.buildContext(componentClient, notebook);
    String systemPrompt = NotebookContext.systemPrompt(notebook, context);
    List<AiClient.ChatMessage> history =
        before.messages().stream().map(m -> new AiClient.ChatMessage(m.role(), m.content())).toList();
    var withNew = new java.util.ArrayList<>(history);
    withNew.add(new AiClient.ChatMessage("user", request.message()));

    String reply;
    try {
      reply = aiClient.chatComplete(modelId, systemPrompt, withNew);
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
    componentClient
        .forEventSourcedEntity(request.session_id())
        .method(ChatSessionEntity::appendMessage)
        .invoke(new ChatSessionEntity.AppendMessage("assistant", reply, Instant.now()));

    ChatSession after = fetch(request.session_id());
    return HttpResponses.ok(new SendMessageResponse(after.chatId(), messagesOf(after)));
  }

  /**
   * {@code context_config} names which sources/notes to include and each one's inclusion mode
   * (full text vs. summary); this port's {@link NotebookContext#buildContext} always assembles
   * the notebook's full linked set (SS8 D-8's same narrowing), so {@code context_config} is
   * accepted but not consulted -- what is returned is the same context {@code /execute} would
   * hand the model, which is what the frontend's context-preview panel needs to show honestly.
   */
  @Post("/context")
  public HttpResponse buildContext(BuildContextRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Notebook notebook = NotebookContext.notebookOf(componentClient, request.notebook_id());
    String context = NotebookContext.buildContext(componentClient, notebook);
    return HttpResponses.ok(
        new BuildContextResponse(
            new ContextPayload(List.copyOf(notebook.sourceIds()), List.copyOf(notebook.noteIds())),
            context.split("\\s+").length, context.length()));
  }

  private String defaultChatModel() {
    DefaultModels defaults = componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::get).invoke();
    return defaults.defaultChatModel();
  }

  private ChatSession fetch(String chatId) {
    try {
      return componentClient.forEventSourcedEntity(chatId).method(ChatSessionEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }

  private List<ChatMessageResponse> messagesOf(ChatSession session) {
    return session.messages().stream()
        .map(m -> new ChatMessageResponse(UUID.randomUUID().toString(), m.role(), m.content(), m.at()))
        .toList();
  }

  private SessionWithMessagesResponse toDetail(ChatSession session) {
    return new SessionWithMessagesResponse(session.chatId(), session.notebookId(), null, messagesOf(session));
  }
}
