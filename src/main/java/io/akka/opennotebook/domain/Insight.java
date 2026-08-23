package io.akka.opennotebook.domain;

/** Typed content attached to a source (R14's raw material for a generated note). */
public record Insight(String insightType, String content) {}
