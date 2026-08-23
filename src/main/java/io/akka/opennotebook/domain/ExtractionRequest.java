package io.akka.opennotebook.domain;

/**
 * What a source was submitted as: text handed over directly, or a URL to fetch.
 *
 * <p>Used only as an in-process value between the endpoint and the workflow — never persisted or
 * sent as a command field directly, so it carries no {@code @TypeName}. Akka's serializer
 * resolves a sealed interface's subtypes only at the top level of a persisted or transmitted
 * type, not for one nested inside another record's field; {@link SourceIngestionWorkflow}
 * flattens it to plain {@code content}/{@code url} strings for anything that crosses the wire.
 */
public sealed interface ExtractionRequest {
  record PlainText(String content) implements ExtractionRequest {}

  record Url(String url) implements ExtractionRequest {}
}
