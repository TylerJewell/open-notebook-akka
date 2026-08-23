package io.akka.opennotebook.domain;

/** R8, R10: the outcome of deleting a notebook. */
public record DeleteResult(int deletedNotes, int deletedSources, int unlinkedSources) {}
