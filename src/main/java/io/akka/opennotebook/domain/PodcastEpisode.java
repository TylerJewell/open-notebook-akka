package io.akka.opennotebook.domain;

import java.time.Instant;

/** One generated (or in-progress) podcast episode (SPEC-001 §Podcasts). */
public record PodcastEpisode(
    String id,
    String notebookId,
    String episodeProfileId,
    String name,
    PodcastStatus status,
    String briefing,
    String outline,
    String transcript,
    String audioBase64,
    String errorMessage,
    Instant createdAt,
    boolean exists) {

  public static PodcastEpisode empty() {
    return new PodcastEpisode(null, null, null, null, null, null, null, null, null, null, null, false);
  }

  public static PodcastEpisode create(
      String id, String notebookId, String episodeProfileId, String name, String briefing, Instant at) {
    return new PodcastEpisode(
        id, notebookId, episodeProfileId, name, PodcastStatus.PENDING, briefing, null, null, null, null, at, true);
  }

  public PodcastEpisode withStatus(PodcastStatus status) {
    return new PodcastEpisode(
        id, notebookId, episodeProfileId, name, status, briefing, outline, transcript, audioBase64, errorMessage, createdAt, exists);
  }

  public PodcastEpisode withOutline(String outline) {
    return new PodcastEpisode(
        id, notebookId, episodeProfileId, name, status, briefing, outline, transcript, audioBase64, errorMessage, createdAt, exists);
  }

  public PodcastEpisode withTranscript(String transcript) {
    return new PodcastEpisode(
        id, notebookId, episodeProfileId, name, status, briefing, outline, transcript, audioBase64, errorMessage, createdAt, exists);
  }

  public PodcastEpisode withCompleted(String audioBase64) {
    return new PodcastEpisode(
        id, notebookId, episodeProfileId, name, PodcastStatus.COMPLETED, briefing, outline, transcript, audioBase64, null, createdAt, exists);
  }

  public PodcastEpisode withFailed(String errorMessage) {
    return new PodcastEpisode(
        id, notebookId, episodeProfileId, name, PodcastStatus.FAILED, briefing, outline, transcript, audioBase64, errorMessage, createdAt, exists);
  }
}
