package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.time.Instant;
import java.util.List;

/** Every chat session, queryable by notebook -- the frontend's {@code GET /api/chat/sessions?
 * notebook_id=}, which the bare-path {@code ChatEndpoint} never needed because it only ever
 * addresses one session at a time by id. */
@Component(id = "chat-sessions-view")
public class ChatSessionsView extends View {

  public record Entry(String chatId, String notebookId, int messageCount, Instant createdAt, Instant updatedAt) {}

  public record Entries(List<Entry> items) {}

  @Consume.FromEventSourcedEntity(ChatSessionEntity.class)
  public static class Sessions extends TableUpdater<Entry> {
    public Effect<Entry> onEvent(ChatSessionEntity.Event event) {
      return switch (event) {
        case ChatSessionEntity.ChatCreated e -> effects().updateRow(new Entry(e.chatId(), e.notebookId(), 0, e.at(), e.at()));
        case ChatSessionEntity.MessageAppended e ->
            effects().updateRow(
                new Entry(rowState().chatId(), rowState().notebookId(), rowState().messageCount() + 1, rowState().createdAt(), e.at()));
        case ChatSessionEntity.ChatDeleted e -> effects().deleteRow();
      };
    }
  }

  // No ORDER BY -- the real runtime requires it to match the WHERE-filtered column (see
  // SourcesView.byNotebook()'s comment); ApiChatEndpoint/ApiSourceChatEndpoint read this
  // unordered (session lists are short and typically one-per-scope in this port).
  @Query("SELECT * AS items FROM sessions WHERE notebookId = :notebookId")
  public QueryEffect<Entries> byNotebook(String notebookId) {
    return queryResult();
  }
}
