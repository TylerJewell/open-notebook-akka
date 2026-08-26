package io.akka.opennotebook.domain;

import java.time.Instant;
import java.util.Set;

/** A notebook's own state: its identity, and which sources, notes, and chat sessions it currently
 * holds. */
public record Notebook(
    String notebookId,
    String name,
    String description,
    boolean archived,
    Set<String> sourceIds,
    Set<String> noteIds,
    Set<String> chatSessionIds,
    Instant createdAt,
    Instant updatedAt,
    boolean deleted) {

  public static Notebook create(String notebookId, String name, String description, Instant now) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Notebook name cannot be empty");
    }
    return new Notebook(
        notebookId, name, description, false, Set.of(), Set.of(), Set.of(), now, now, false);
  }

  public boolean exists() {
    return notebookId != null && !deleted;
  }

  public Notebook withSourceLinked(String sourceId, Instant now) {
    var linked = new java.util.LinkedHashSet<>(sourceIds);
    linked.add(sourceId);
    return new Notebook(
        notebookId, name, description, archived, linked, noteIds, chatSessionIds, createdAt, now, deleted);
  }

  public Notebook withNoteLinked(String noteId, Instant now) {
    var linked = new java.util.LinkedHashSet<>(noteIds);
    linked.add(noteId);
    return new Notebook(
        notebookId, name, description, archived, sourceIds, linked, chatSessionIds, createdAt, now, deleted);
  }

  public Notebook withChatSessionLinked(String chatId, Instant now) {
    var linked = new java.util.LinkedHashSet<>(chatSessionIds);
    linked.add(chatId);
    return new Notebook(
        notebookId, name, description, archived, sourceIds, noteIds, linked, createdAt, now, deleted);
  }

  public Notebook withDeleted(Instant now) {
    return new Notebook(
        notebookId, name, description, archived, Set.of(), Set.of(), Set.of(), createdAt, now, true);
  }
}
