package io.akka.opennotebook.domain;

/** Typed content attached to a source (R14's raw material for a generated note).
 *
 * <p>{@code id} is local to the owning source, not a database-wide key — the original addresses
 * an insight through a single global {@code SourceInsight} record id and a graph query back to
 * its source ({@code insight.get_source()}); an Akka View's one-row-per-entity model has no
 * matching secondary index without a dedicated entity per insight, so {@code
 * ApiInsightEndpoint}'s global routes expose the composite {@code "<sourceId>:<id>"} as the
 * insight's id instead. Same capability, an opaque token the caller never has to construct. */
public record Insight(String id, String insightType, String content) {}
