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
import io.akka.opennotebook.application.ChatSessionEntity;
import io.akka.opennotebook.application.ChatSessionsView;
import io.akka.opennotebook.application.DefaultModelsEntity;
import io.akka.opennotebook.application.SourceEntity;
import io.akka.opennotebook.domain.ChatSession;
import io.akka.opennotebook.domain.DefaultModels;
import io.akka.opennotebook.domain.Source;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The frontend's {@code frontend/src/lib/api/source-chat.ts} -- a capability with no bare-path
 * equivalent at all (the earlier sessions only ported notebook-scoped chat). Reuses {@link
 * ChatSessionEntity} rather than a new entity type: the entity's {@code notebookId} field is used
 * here to hold the source's id instead -- the entity has no rule anywhere that inspects what kind
 * of id that field holds, only that it identifies the thing the session is scoped to, and
 * {@link io.akka.opennotebook.application.NotebookContext} (which does assume a notebook id) is
 * never called from this endpoint. A source-chat session id is never routed through {@code
 * NotebookDeletionWorkflow} either, so the two scopes never collide.
 */
@HttpEndpoint("/api/sources")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiSourceChatEndpoint extends AbstractHttpEndpoint {

  public record CreateSessionRequest(String title, String model_override) {}

  public record SessionResponse(String id, String source_id, String title, Instant created) {}

  public record MessageResponse(String id, String type, String content, Instant timestamp) {}

  public record SessionWithMessagesResponse(String id, String source_id, List<MessageResponse> messages) {}

  public record SendMessageRequest(String message, String model_override) {}

  public record ChatEvent(String type, String content, String message) {}

  private final ComponentClient componentClient;
  private final AiClient aiClient;

  public ApiSourceChatEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
    this.aiClient = new AiClient(componentClient);
  }

  @Get("/{sourceId}/chat/sessions")
  public HttpResponse listSessions(String sourceId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    ChatSessionsView.Entries entries = componentClient.forView().method(ChatSessionsView::byNotebook).invoke(sourceId);
    return HttpResponses.ok(
        entries.items().stream().map(e -> new SessionResponse(e.chatId(), sourceId, null, e.createdAt())).toList());
  }

  @Post("/{sourceId}/chat/sessions")
  public HttpResponse createSession(String sourceId, CreateSessionRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String chatId = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(chatId)
        .method(ChatSessionEntity::create)
        .invoke(new ChatSessionEntity.Create(sourceId, Instant.now()));
    return HttpResponses.created(new SessionResponse(chatId, sourceId, request.title(), Instant.now()), "/api/sources/" + sourceId + "/chat/sessions/" + chatId);
  }

  @Get("/{sourceId}/chat/sessions/{sessionId}")
  public HttpResponse getSession(String sourceId, String sessionId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    ChatSession session = fetch(sessionId);
    if (session == null) return HttpResponses.notFound("Chat session not found");
    return HttpResponses.ok(new SessionWithMessagesResponse(session.chatId(), sourceId, messagesOf(session)));
  }

  @Put("/{sourceId}/chat/sessions/{sessionId}")
  public HttpResponse updateSession(String sourceId, String sessionId, CreateSessionRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    ChatSession session = fetch(sessionId);
    if (session == null) return HttpResponses.notFound("Chat session not found");
    return HttpResponses.ok(new SessionResponse(session.chatId(), sourceId, request.title(), session.createdAt()));
  }

  @Delete("/{sourceId}/chat/sessions/{sessionId}")
  public HttpResponse deleteSession(String sourceId, String sessionId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forEventSourcedEntity(sessionId).method(ChatSessionEntity::delete).invoke(new ChatSessionEntity.ChatDeleted(Instant.now()));
      return HttpResponses.ok();
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  /**
   * RENDERING.md R4 wire-contract match for {@code useSourceChat}: the original streams
   * token-by-token; {@link AiClient#chatComplete} does not stream, so this emits the same event
   * shape the frontend already parses ({@code context_indicators} then {@code ai_message}) as two
   * chunks of a real, if not incremental, SSE response rather than one plain JSON body.
   */
  @Post("/{sourceId}/chat/sessions/{sessionId}/messages")
  public HttpResponse sendMessage(String sourceId, String sessionId, SendMessageRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    ChatSession before = fetch(sessionId);
    if (before == null) {
      return HttpResponses.serverSentEvents(akka.stream.javadsl.Source.single(new ChatEvent("error", null, "Chat session not found")));
    }
    Source source;
    try {
      source = componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
    } catch (Exception e) {
      return HttpResponses.serverSentEvents(akka.stream.javadsl.Source.single(new ChatEvent("error", null, "Source not found")));
    }
    componentClient
        .forEventSourcedEntity(sessionId)
        .method(ChatSessionEntity::appendMessage)
        .invoke(new ChatSessionEntity.AppendMessage("user", request.message(), Instant.now()));

    String modelId = request.model_override() != null ? request.model_override() : defaultChatModel();
    String systemPrompt =
        "You are a research assistant discussing one document.\n\n# DOCUMENT: " + source.title()
            + "\n\n" + (source.fullText() == null ? "" : source.fullText());
    List<AiClient.ChatMessage> history =
        before.messages().stream().map(m -> new AiClient.ChatMessage(m.role(), m.content())).toList();
    var withNew = new java.util.ArrayList<>(history);
    withNew.add(new AiClient.ChatMessage("user", request.message()));

    String reply;
    try {
      reply = aiClient.chatComplete(modelId, systemPrompt, withNew);
    } catch (Exception e) {
      return HttpResponses.serverSentEvents(akka.stream.javadsl.Source.single(new ChatEvent("error", null, e.getMessage())));
    }
    componentClient
        .forEventSourcedEntity(sessionId)
        .method(ChatSessionEntity::appendMessage)
        .invoke(new ChatSessionEntity.AppendMessage("assistant", reply, Instant.now()));

    var events =
        akka.stream.javadsl.Source.from(
            List.of(
                new ChatEvent("context_indicators", "source:" + sourceId, null),
                new ChatEvent("ai_message", reply, null)));
    return HttpResponses.serverSentEvents(events);
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

  private List<MessageResponse> messagesOf(ChatSession session) {
    return session.messages().stream()
        .map(m -> new MessageResponse(UUID.randomUUID().toString(), m.role(), m.content(), m.at()))
        .toList();
  }
}
