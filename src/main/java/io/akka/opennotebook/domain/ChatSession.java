package io.akka.opennotebook.domain;

import java.time.Instant;
import java.util.List;

/** A chat thread scoped to one notebook (SPEC-001 §Chat) — the message history the source keeps
 * per LangGraph checkpoint, here persisted directly as the entity's own event-sourced state. */
public record ChatSession(
    String chatId, String notebookId, List<ChatMessage> messages, Instant createdAt, boolean exists) {

  public record ChatMessage(String role, String content, Instant at) {}

  public static ChatSession empty() {
    return new ChatSession(null, null, List.of(), null, false);
  }

  public static ChatSession create(String chatId, String notebookId, Instant now) {
    return new ChatSession(chatId, notebookId, List.of(), now, true);
  }

  public ChatSession withMessage(String role, String content, Instant at) {
    var updated = new java.util.ArrayList<>(messages);
    updated.add(new ChatMessage(role, content, at));
    return new ChatSession(chatId, notebookId, List.copyOf(updated), createdAt, exists);
  }

  public ChatSession withDeleted() {
    return new ChatSession(chatId, notebookId, messages, createdAt, false);
  }
}
