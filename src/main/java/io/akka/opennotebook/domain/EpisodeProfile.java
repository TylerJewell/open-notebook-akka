package io.akka.opennotebook.domain;

import java.time.Instant;

/** A reusable recipe for generating a podcast episode (SPEC-001 §Podcasts). */
public record EpisodeProfile(
    String id,
    String name,
    String description,
    String outlineModelId,
    String transcriptModelId,
    String speakerProfileId,
    String defaultBriefing,
    int numSegments,
    Instant createdAt,
    boolean exists) {

  public static EpisodeProfile empty() {
    return new EpisodeProfile(null, null, null, null, null, null, null, 5, null, false);
  }

  public static EpisodeProfile create(
      String id,
      String name,
      String description,
      String outlineModelId,
      String transcriptModelId,
      String speakerProfileId,
      String defaultBriefing,
      int numSegments,
      Instant at) {
    return new EpisodeProfile(
        id, name, description, outlineModelId, transcriptModelId, speakerProfileId, defaultBriefing, numSegments, at, true);
  }
}
