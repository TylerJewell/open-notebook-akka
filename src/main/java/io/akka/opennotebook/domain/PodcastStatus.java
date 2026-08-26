package io.akka.opennotebook.domain;

public enum PodcastStatus {
  PENDING,
  GENERATING_OUTLINE,
  GENERATING_TRANSCRIPT,
  GENERATING_AUDIO,
  COMPLETED,
  FAILED
}
