package io.akka.opennotebook.domain;

import java.util.List;

/**
 * One chunk of a source's or note's text, embedded for search (SPEC-001 §Search) — the port's
 * equivalent of a {@code source_embedding}/{@code note_embedding} row.
 */
public record EmbeddingChunk(
    String id, String ownerType, String ownerId, int chunkIndex, String text, List<Double> vector, boolean exists) {

  public static EmbeddingChunk empty() {
    return new EmbeddingChunk(null, null, null, 0, null, List.of(), false);
  }

  public static EmbeddingChunk create(
      String id, String ownerType, String ownerId, int chunkIndex, String text, List<Double> vector) {
    return new EmbeddingChunk(id, ownerType, ownerId, chunkIndex, text, vector, true);
  }
}
