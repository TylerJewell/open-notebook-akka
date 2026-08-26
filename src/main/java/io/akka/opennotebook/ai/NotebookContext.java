package io.akka.opennotebook.ai;

import akka.javasdk.client.ComponentClient;
import io.akka.opennotebook.application.NoteEntity;
import io.akka.opennotebook.application.NotebookEntity;
import io.akka.opennotebook.application.SourceEntity;
import io.akka.opennotebook.domain.Note;
import io.akka.opennotebook.domain.Notebook;
import io.akka.opennotebook.domain.Source;

/**
 * Assembles a notebook's sources and notes into the CONTEXT block chat and ask hand the model
 * (source: {@code open_notebook/utils/context_builder.py}), tagging each item with the
 * {@code type:id} form the system prompt's citing instructions require (e.g. {@code [source:abc]}).
 */
public final class NotebookContext {

  private NotebookContext() {}

  public static Notebook notebookOf(ComponentClient componentClient, String notebookId) {
    return componentClient.forEventSourcedEntity(notebookId).method(NotebookEntity::get).invoke();
  }

  public static String buildContext(ComponentClient componentClient, Notebook notebook) {
    StringBuilder sb = new StringBuilder();
    for (String sourceId : notebook.sourceIds()) {
      Source source;
      try {
        source = componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
      } catch (Exception e) {
        continue;
      }
      if (source.fullText() == null || source.fullText().isBlank()) continue;
      sb.append("## SOURCE: ").append(source.title()).append(" [source:").append(sourceId).append("]\n");
      sb.append(source.fullText()).append("\n\n");
      for (var insight : source.insights()) {
        sb.append("### INSIGHT (").append(insight.insightType()).append("): ").append(insight.content())
            .append("\n\n");
      }
    }
    for (String noteId : notebook.noteIds()) {
      Note note;
      try {
        note = componentClient.forEventSourcedEntity(noteId).method(NoteEntity::get).invoke();
      } catch (Exception e) {
        continue;
      }
      sb.append("## NOTE: ").append(note.title()).append(" [note:").append(noteId).append("]\n");
      sb.append(note.content()).append("\n\n");
    }
    return sb.toString();
  }

  public static String systemPrompt(Notebook notebook, String context) {
    StringBuilder sb = new StringBuilder();
    sb.append("# SYSTEM ROLE\n")
        .append("You are a cognitive study assistant that helps users research and learn by engaging in ")
        .append("focused discussions about documents in their workspace.\n\n");
    if (notebook != null) {
      sb.append("# PROJECT INFORMATION\n\n**Name:** ").append(notebook.name())
          .append("\n**Description:** ").append(notebook.description() == null ? "" : notebook.description())
          .append("\n\n");
    }
    if (context != null && !context.isBlank()) {
      sb.append("# CONTEXT\n\nThe user has selected this context to help you with your response:\n\n")
          .append(context).append("\n\n");
    }
    sb.append("# CITING INSTRUCTIONS\n\n")
        .append("If your answer is based on any item in the context, cite it by adding the item's id in ")
        .append("brackets, e.g. [source:abc123] or [note:abc123]. Use ids exactly as given; do not invent them.\n");
    return sb.toString();
  }
}
