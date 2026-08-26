package io.akka.opennotebook.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.opennotebook.domain.ChatSession;
import java.time.Instant;

/** A notebook-scoped chat thread's own message history (SPEC-001 §Chat). */
@Component(id = "chat-session")
public class ChatSessionEntity extends EventSourcedEntity<ChatSession, ChatSessionEntity.Event> {

  public sealed interface Event {}

  @TypeName("chat-created")
  public record ChatCreated(String chatId, String notebookId, Instant at) implements Event {}

  @TypeName("chat-message-appended")
  public record MessageAppended(String role, String content, Instant at) implements Event {}

  @TypeName("chat-deleted")
  public record ChatDeleted(Instant at) implements Event {}

  public record Create(String notebookId, Instant now) {}

  public record AppendMessage(String role, String content, Instant now) {}

  @Override
  public ChatSession emptyState() {
    return ChatSession.empty();
  }

  public Effect<Done> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Chat session already exists");
    }
    var event = new ChatCreated(commandContext().entityId(), command.notebookId(), command.now());
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> appendMessage(AppendMessage command) {
    if (!currentState().exists()) {
      return effects().error("Chat session not found");
    }
    var event = new MessageAppended(command.role(), command.content(), command.now());
    return effects().persist(event).thenReply(s -> Done.getInstance());
  }

  public Effect<Done> delete(ChatDeleted command) {
    if (!currentState().exists()) {
      return effects().error("Chat session not found");
    }
    return effects().persist(command).thenReply(s -> Done.getInstance());
  }

  public ReadOnlyEffect<ChatSession> get() {
    if (!currentState().exists()) {
      return effects().error("Chat session not found");
    }
    return effects().reply(currentState());
  }

  @Override
  public ChatSession applyEvent(Event event) {
    return switch (event) {
      case ChatCreated e -> ChatSession.create(e.chatId(), e.notebookId(), e.at());
      case MessageAppended e -> currentState().withMessage(e.role(), e.content(), e.at());
      case ChatDeleted e -> currentState().withDeleted();
    };
  }
}
