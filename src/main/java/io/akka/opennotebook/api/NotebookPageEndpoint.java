package io.akka.opennotebook.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import io.akka.opennotebook.application.NotebookEntity;
import io.akka.opennotebook.application.SourceEntity;
import io.akka.opennotebook.domain.Notebook;

/**
 * A minimal server-rendered view of a notebook and its sources — RENDERING.md R7: the source's
 * own frontend (SourceCard.tsx, ProcessingStep.tsx) shows exactly this state (a source's title
 * and processing status inside its notebook) to a person watching it, so that state's screen is
 * in this port's slice even though the slice itself is a backend capability. This page renders
 * the same slice-owned facts in plain markup rather than reproducing the original's design
 * system — see SPEC-001 §4 D-8 and gui/manifest.json's declared difference.
 */
@HttpEndpoint("/ui")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class NotebookPageEndpoint {

  private final ComponentClient componentClient;

  public NotebookPageEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/notebooks/{notebookId}")
  public HttpResponse page(String notebookId) {
    Notebook notebook;
    try {
      notebook = componentClient.forEventSourcedEntity(notebookId).method(NotebookEntity::get).invoke();
    } catch (Exception e) {
      return HttpResponses.notFound("Notebook not found");
    }

    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        .append("<title>").append(escape(notebook.name())).append("</title></head><body>")
        .append("<h1>").append(escape(notebook.name())).append("</h1>")
        .append("<p>").append(escape(notebook.description())).append("</p>")
        .append("<h2>Sources</h2><ul>");

    for (String sourceId : notebook.sourceIds()) {
      try {
        var source = componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
        html.append("<li class=\"source-card\">")
            .append("<span class=\"source-title\">").append(escape(source.title())).append("</span>")
            .append(" &mdash; <span class=\"source-status\">").append(source.status()).append("</span>")
            .append("</li>");
      } catch (Exception ignored) {
        // Source was deleted between listing and fetching; skip it rather than fail the page.
      }
    }

    html.append("</ul></body></html>");

    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .withEntity(ContentTypes.TEXT_HTML_UTF8, html.toString());
  }

  private static String escape(String s) {
    return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
