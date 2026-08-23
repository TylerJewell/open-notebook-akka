package io.akka.opennotebook.domain;

/** R2: a source's extraction lifecycle is exactly these four states, in this order. */
public enum SourceStatus {
  NEW,
  RUNNING,
  COMPLETED,
  FAILED
}
