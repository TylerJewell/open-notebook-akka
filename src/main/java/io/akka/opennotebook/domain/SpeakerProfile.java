package io.akka.opennotebook.domain;

import java.time.Instant;
import java.util.List;

/** A named set of voices for podcast generation (SPEC-001 §Podcasts). */
public record SpeakerProfile(
    String id, String name, String description, String voiceModelId, List<String> speakerNames, Instant createdAt, boolean exists) {

  public static SpeakerProfile empty() {
    return new SpeakerProfile(null, null, null, null, List.of(), null, false);
  }

  public static SpeakerProfile create(
      String id, String name, String description, String voiceModelId, List<String> speakerNames, Instant at) {
    return new SpeakerProfile(id, name, description, voiceModelId, speakerNames, at, true);
  }
}
