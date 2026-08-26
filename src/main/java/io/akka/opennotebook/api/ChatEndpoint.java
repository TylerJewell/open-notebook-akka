package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.opennotebook.ai.AiClient;
import io.akka.opennotebook.ai.NotebookContext;
import io.akka.opennotebook.application.ChatSessionEntity;
import io.akka.opennotebook.application.DefaultModelsEntity;
import io.akka.opennotebook.domain.ChatSession;
import io.akka.opennotebook.domain.DefaultModels;
import io.akka.opennotebook.domain.Notebook;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Chat and ask (SPEC-001 §Chat) — the source's {@code graphs/chat.py} and {@code graphs/ask.py},
 * collapsed to a single completion call per turn: retrieval in the source is a multi-step
 * search-then-answer graph over embeddings (ported separately, §Search); here the full notebook
 * context is handed to the model directly, which is what chat already does when no narrower
 * context is selected. Documented as a narrowing in README.
 */
@HttpEndpoint("/notebooks")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ChatEndpoint extends AbstractHttpEndpoint {

  public record CreateSessionResponse(String chatId, String notebookId) {}

  public record SendMessageRequest(String content, String modelId) {}

  public record MessageResponse(String role, String content) {}

  public record SessionResponse(String chatId, String notebookId, List<MessageResponse> messages) {}

  public record AskRequest(String question, String modelId) {}

  public record AskResponse(String answer) {}

  private final ComponentClient componentClient;
  private final AiClient aiClient;

  public ChatEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
    this.aiClient = new AiClient(componentClient);
  }

  @Post("/{notebookId}/chat/sessions")
  public HttpResponse createSession(String notebookId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String chatId = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(chatId)
        .method(ChatSessionEntity::create)
        .invoke(new ChatSessionEntity.Create(notebookId, Instant.now()));
    componentClient
        .forEventSourcedEntity(notebookId)
        .method(io.akka.opennotebook.application.NotebookEntity::linkChatSession)
        .invoke(new io.akka.opennotebook.application.NotebookEntity.ChatSessionLinked(chatId, Instant.now()));
    return HttpResponses.created(new CreateSessionResponse(chatId, notebookId), "/notebooks/" + notebookId + "/chat/sessions/" + chatId);
  }

  @Get("/{notebookId}/chat/sessions/{chatId}")
  public HttpResponse getSession(String notebookId, String chatId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      ChatSession session =
          componentClient.forEventSourcedEntity(chatId).method(ChatSessionEntity::get).invoke();
      return HttpResponses.ok(toApi(session));
    } catch (Exception e) {
      return HttpResponses.notFound("Chat session not found");
    }
  }

  @Post("/{notebookId}/chat/sessions/{chatId}/messages")
  public HttpResponse sendMessage(String notebookId, String chatId, SendMessageRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    if (request.content() == null || request.content().isBlank()) {
      return HttpResponses.badRequest("Message content cannot be empty");
    }
    ChatSession before;
    try {
      before = componentClient.forEventSourcedEntity(chatId).method(ChatSessionEntity::get).invoke();
    } catch (Exception e) {
      return HttpResponses.notFound("Chat session not found");
    }

    componentClient
        .forEventSourcedEntity(chatId)
        .method(ChatSessionEntity::appendMessage)
        .invoke(new ChatSessionEntity.AppendMessage("user", request.content(), Instant.now()));

    String modelId = resolveModel(request.modelId(), DefaultModels::defaultChatModel);
    Notebook notebook = NotebookContext.notebookOf(componentClient, notebookId);
    String context = NotebookContext.buildContext(componentClient, notebook);
    String systemPrompt = NotebookContext.systemPrompt(notebook, context);

    List<AiClient.ChatMessage> history =
        before.messages().stream().map(m -> new AiClient.ChatMessage(m.role(), m.content())).toList();
    var withNew = new java.util.ArrayList<>(history);
    withNew.add(new AiClient.ChatMessage("user", request.content()));

    String reply;
    try {
      reply = aiClient.chatComplete(modelId, systemPrompt, withNew);
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }

    componentClient
        .forEventSourcedEntity(chatId)
        .method(ChatSessionEntity::appendMessage)
        .invoke(new ChatSessionEntity.AppendMessage("assistant", reply, Instant.now()));

    ChatSession after = componentClient.forEventSourcedEntity(chatId).method(ChatSessionEntity::get).invoke();
    return HttpResponses.ok(toApi(after));
  }

  @Post("/{notebookId}/ask")
  public HttpResponse ask(String notebookId, AskRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    if (request.question() == null || request.question().isBlank()) {
      return HttpResponses.badRequest("Question cannot be empty");
    }
    String modelId = resolveModel(request.modelId(), DefaultModels::defaultChatModel);
    Notebook notebook = NotebookContext.notebookOf(componentClient, notebookId);
    String context = NotebookContext.buildContext(componentClient, notebook);
    String systemPrompt = NotebookContext.systemPrompt(notebook, context);
    try {
      String answer =
          aiClient.chatComplete(modelId, systemPrompt, List.of(new AiClient.ChatMessage("user", request.question())));
      return HttpResponses.ok(new AskResponse(answer));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  private String resolveModel(String explicit, java.util.function.Function<DefaultModels, String> pick) {
    if (explicit != null && !explicit.isBlank()) return explicit;
    DefaultModels defaults =
        componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::get).invoke();
    return pick.apply(defaults);
  }

  private SessionResponse toApi(ChatSession session) {
    return new SessionResponse(
        session.chatId(),
        session.notebookId(),
        session.messages().stream().map(m -> new MessageResponse(m.role(), m.content())).toList());
  }
}
