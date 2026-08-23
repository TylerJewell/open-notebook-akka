package io.akka.opennotebook.domain;

import java.time.Instant;
import java.util.Set;

/** A note's own lifecycle (R13–R15): human-authored or generated from a source's insight. */
public record Note(
    String noteId,
    String title,
    String content,
    NoteType noteType,
    Set<String> notebookIds,
    Instant createdAt,
    Instant updatedAt,
    boolean deleted) {

  public static Note create(
      String noteId,
      String title,
      String content,
      NoteType noteType,
      Set<String> notebookIds,
      Instant now) {
    // R13: content must not be empty when given at all.
    if (content != null && content.isBlank()) {
      throw new IllegalArgumentException("Note content cannot be empty");
    }
    return new Note(noteId, title, content, noteType, Set.copyOf(notebookIds), now, now, false);
  }

  /** R14: an AI note generated from a source's insight, titled and worded from it alone. */
  public static Note fromInsight(
      String noteId, String sourceTitle, Insight insight, String notebookId, Instant now) {
    String title = "%s from source %s".formatted(insight.insightType(), sourceTitle);
    return create(noteId, title, insight.content(), NoteType.AI, Set.of(notebookId), now);
  }

  public boolean exists() {
    return noteId != null && !deleted;
  }

  public Note withNotebookLinked(String notebookId, Instant now) {
    var linked = new java.util.LinkedHashSet<>(notebookIds);
    linked.add(notebookId);
    return new Note(noteId, title, content, noteType, linked, createdAt, now, deleted);
  }

  public Note withDeleted(Instant now) {
    return new Note(noteId, title, content, noteType, Set.of(), createdAt, now, true);
  }
}
