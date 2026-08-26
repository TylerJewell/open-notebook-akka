package io.akka.opennotebook.application;

import static java.time.Duration.ofSeconds;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import io.akka.opennotebook.ai.AiClient;
import io.akka.opennotebook.ai.NotebookContext;
import io.akka.opennotebook.domain.EpisodeProfile;
import io.akka.opennotebook.domain.Notebook;
import io.akka.opennotebook.domain.PodcastStatus;
import io.akka.opennotebook.domain.SpeakerProfile;
import java.time.Instant;
import java.util.List;

/**
 * Outline → transcript → audio (SPEC-001 §Podcasts) — the source's {@code
 * commands/podcast_commands.py} generation pipeline, driven here as a durable Workflow instead
 * of a background job queue (the same substitution {@link SourceIngestionWorkflow} already makes
 * for source ingestion). A single narrator voice and a single TTS call over the full transcript
 * stand in for the source's per-segment, multi-speaker synthesis — a narrower rebuild of one
 * capability (episode generation), not an excluded one; see port-log for the full note.
 */
@Component(id = "podcast-generation")
public class PodcastGenerationWorkflow extends Workflow<PodcastGenerationWorkflow.State> {

  public record State(String episodeId, String notebookId, String episodeProfileId, String briefing) {}

  public record Start(String episodeId, String notebookId, String episodeProfileId, String name, String briefing) {}

  private final ComponentClient componentClient;
  private final AiClient aiClient;

  public PodcastGenerationWorkflow(ComponentClient componentClient) {
    this.componentClient = componentClient;
    this.aiClient = new AiClient(componentClient);
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(ofSeconds(60)).build();
  }

  public Effect<Done> start(Start command) {
    componentClient
        .forEventSourcedEntity(command.episodeId())
        .method(PodcastEpisodeEntity::create)
        .invoke(
            new PodcastEpisodeEntity.Create(
                command.notebookId(), command.episodeProfileId(), command.name(), command.briefing(), Instant.now()));
    return effects()
        .updateState(new State(command.episodeId(), command.notebookId(), command.episodeProfileId(), command.briefing()))
        .transitionTo(PodcastGenerationWorkflow::outlineStep)
        .thenReply(Done.getInstance());
  }

  @StepName("outline")
  private StepEffect outlineStep() {
    try {
      EpisodeProfile profile =
          componentClient
              .forKeyValueEntity(currentState().episodeProfileId())
              .method(EpisodeProfileEntity::get)
              .invoke();
      Notebook notebook = NotebookContext.notebookOf(componentClient, currentState().notebookId());
      String context = NotebookContext.buildContext(componentClient, notebook);
      String prompt =
          "You are producing the outline for a podcast episode. Briefing: "
              + currentState().briefing()
              + "\n\nSource material:\n"
              + context
              + "\n\nWrite a numbered outline with "
              + profile.numSegments()
              + " segments.";
      String outline =
          aiClient.chatComplete(profile.outlineModelId(), prompt, List.of(new AiClient.ChatMessage("user", prompt)));

      componentClient
          .forEventSourcedEntity(currentState().episodeId())
          .method(PodcastEpisodeEntity::setOutline)
          .invoke(new PodcastEpisodeEntity.OutlineSet(outline, Instant.now()));
      componentClient
          .forEventSourcedEntity(currentState().episodeId())
          .method(PodcastEpisodeEntity::setStatus)
          .invoke(new PodcastEpisodeEntity.StatusChanged(PodcastStatus.GENERATING_TRANSCRIPT, Instant.now()));

      return stepEffects().thenTransitionTo(PodcastGenerationWorkflow::transcriptStep);
    } catch (Exception e) {
      return fail(e.getMessage());
    }
  }

  @StepName("transcript")
  private StepEffect transcriptStep() {
    try {
      EpisodeProfile profile =
          componentClient
              .forKeyValueEntity(currentState().episodeProfileId())
              .method(EpisodeProfileEntity::get)
              .invoke();
      var episode =
          componentClient.forEventSourcedEntity(currentState().episodeId()).method(PodcastEpisodeEntity::get).invoke();
      String prompt =
          "Turn this outline into a natural spoken-word podcast transcript:\n\n" + episode.outline();
      String transcript =
          aiClient.chatComplete(
              profile.transcriptModelId(), prompt, List.of(new AiClient.ChatMessage("user", prompt)));

      componentClient
          .forEventSourcedEntity(currentState().episodeId())
          .method(PodcastEpisodeEntity::setTranscript)
          .invoke(new PodcastEpisodeEntity.TranscriptSet(transcript, Instant.now()));
      componentClient
          .forEventSourcedEntity(currentState().episodeId())
          .method(PodcastEpisodeEntity::setStatus)
          .invoke(new PodcastEpisodeEntity.StatusChanged(PodcastStatus.GENERATING_AUDIO, Instant.now()));

      return stepEffects().thenTransitionTo(PodcastGenerationWorkflow::audioStep);
    } catch (Exception e) {
      return fail(e.getMessage());
    }
  }

  @StepName("audio")
  private StepEffect audioStep() {
    try {
      EpisodeProfile profile =
          componentClient
              .forKeyValueEntity(currentState().episodeProfileId())
              .method(EpisodeProfileEntity::get)
              .invoke();
      SpeakerProfile speaker =
          componentClient
              .forKeyValueEntity(profile.speakerProfileId())
              .method(SpeakerProfileEntity::get)
              .invoke();
      var episode =
          componentClient.forEventSourcedEntity(currentState().episodeId()).method(PodcastEpisodeEntity::get).invoke();

      byte[] audio = aiClient.textToSpeech(speaker.voiceModelId(), episode.transcript(), null);

      componentClient
          .forEventSourcedEntity(currentState().episodeId())
          .method(PodcastEpisodeEntity::complete)
          .invoke(new PodcastEpisodeEntity.Completed(AiClient.base64(audio), Instant.now()));

      return stepEffects().thenEnd();
    } catch (Exception e) {
      return fail(e.getMessage());
    }
  }

  private StepEffect fail(String message) {
    componentClient
        .forEventSourcedEntity(currentState().episodeId())
        .method(PodcastEpisodeEntity::fail)
        .invoke(new PodcastEpisodeEntity.Failed(message, Instant.now()));
    return stepEffects().thenEnd();
  }
}
