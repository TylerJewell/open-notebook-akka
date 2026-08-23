package io.akka.opennotebook.domain;

/** R11: what a notebook delete would do, computed without changing any state. */
public record DeletePreview(int noteCount, int exclusiveSourceCount, int sharedSourceCount) {}
