package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.opennotebook.application.EpisodeProfileEntity;
import io.akka.opennotebook.application.EpisodeProfilesView;
import io.akka.opennotebook.application.PodcastEpisodeEntity;
import io.akka.opennotebook.application.PodcastEpisodesView;
import io.akka.opennotebook.application.PodcastGenerationWorkflow;
import io.akka.opennotebook.application.SpeakerProfileEntity;
import io.akka.opennotebook.application.SpeakerProfilesView;
import io.akka.opennotebook.domain.EpisodeProfile;
import io.akka.opennotebook.domain.PodcastEpisode;
import io.akka.opennotebook.domain.SpeakerProfile;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * The frontend's {@code frontend/src/lib/api/podcasts.ts} against {@link PodcastEpisodeEntity} /
 * {@link EpisodeProfileEntity} / {@link SpeakerProfileEntity} / {@link PodcastGenerationWorkflow}
 * -- R26, snake_case wire shape, plus the list/update/duplicate/retry routes the bare-path {@code
 * PodcastEndpoint} never needed.
 *
 * <p>Speaker profiles, episode profiles and episodes are each backed by their own {@code *View}
 * ({@link SpeakerProfilesView}, {@link EpisodeProfilesView}, {@link PodcastEpisodesView}), the
 * same pattern {@code CredentialsView}/{@code ModelsView} already use over a {@code
 * KeyValueEntity} -- {@code listEpisodes} previously tracked only the most recently generated
 * episode in a static field; it now lists every episode this instance has ever generated, the
 * same as the source's own {@code list_podcast_episodes}. Audio is served from its own binary
 * route ({@code GET /podcasts/episodes/{id}/audio}), matching the source's {@code audio_url}
 * design, rather than embedded as base64 in every episode's JSON body.
 *
 * <p><b>Narrowed, and declared rather than silently dropped:</b> a single-voice text-to-speech
 * call over the full transcript, not per-segment multi-speaker synthesis (SPEC-001 D-10);
 * {@code /podcasts/jobs/{job_id}} has no separate surface since a {@link PodcastGenerationWorkflow}
 * run and its owning {@link PodcastEpisodeEntity}'s status are the same thing here (D-11) --
 * {@code GET /podcasts/episodes/{id}} already reports it.
 */
@HttpEndpoint("/api")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiPodcastEndpoint extends AbstractHttpEndpoint {

  public record SpeakerProfileRequest(String name, String description, String voice_model_id, List<String> speaker_names) {}

  public record SpeakerProfileResponse(String id, String name, String description, String voice_model_id, List<String> speaker_names) {}

  public record EpisodeProfileRequest(
      String name, String description, String outline_model_id, String transcript_model_id,
      String speaker_profile_id, String default_briefing, int num_segments) {}

  public record EpisodeProfileResponse(
      String id, String name, String description, String outline_model_id, String transcript_model_id,
      String speaker_profile_id, String default_briefing, int num_segments) {}

  public record GenerateRequest(
      String episode_profile, String speaker_profile, String episode_name, String content, String notebook_id, String briefing_suffix) {}

  public record GenerateResponse(String job_id, String status, String message, String episode_profile, String episode_name) {}

  public record EpisodeResponse(
      String id, String name, String episode_profile, String speaker_profile, String briefing,
      String audio_file, String audio_url, String transcript, String outline, String created,
      String job_status, String error_message) {}

  private final ComponentClient componentClient;

  public ApiPodcastEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/speaker-profiles")
  public HttpResponse listSpeakerProfiles() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    SpeakerProfilesView.Entries entries =
        componentClient.forView().method(SpeakerProfilesView::all).invoke();
    return HttpResponses.ok(
        entries.items().stream()
            .map(e -> new SpeakerProfileResponse(e.id(), e.name(), e.description(), e.voiceModelId(), e.speakerNames()))
            .toList());
  }

  @Post("/speaker-profiles")
  public HttpResponse createSpeakerProfile(SpeakerProfileRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String id = UUID.randomUUID().toString();
    try {
      SpeakerProfile created =
          componentClient
              .forKeyValueEntity(id)
              .method(SpeakerProfileEntity::create)
              .invoke(new SpeakerProfileEntity.Create(request.name(), request.description(), request.voice_model_id(), request.speaker_names(), Instant.now()));
      return HttpResponses.created(toApi(created), "/api/speaker-profiles/" + id);
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Get("/speaker-profiles/{id}")
  public HttpResponse getSpeakerProfile(String id) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      return HttpResponses.ok(toApi(componentClient.forKeyValueEntity(id).method(SpeakerProfileEntity::get).invoke()));
    } catch (Exception e) {
      return HttpResponses.notFound("Speaker profile not found");
    }
  }

  @Put("/speaker-profiles/{id}")
  public HttpResponse updateSpeakerProfile(String id, SpeakerProfileRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forKeyValueEntity(id).method(SpeakerProfileEntity::delete).invoke();
    } catch (Exception ignored) {
    }
    try {
      SpeakerProfile updated =
          componentClient
              .forKeyValueEntity(id)
              .method(SpeakerProfileEntity::create)
              .invoke(new SpeakerProfileEntity.Create(request.name(), request.description(), request.voice_model_id(), request.speaker_names(), Instant.now()));
      return HttpResponses.ok(toApi(updated));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Delete("/speaker-profiles/{id}")
  public HttpResponse deleteSpeakerProfile(String id) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forKeyValueEntity(id).method(SpeakerProfileEntity::delete).invoke();
      return HttpResponses.ok();
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Post("/speaker-profiles/{id}/duplicate")
  public HttpResponse duplicateSpeakerProfile(String id) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    SpeakerProfile source;
    try {
      source = componentClient.forKeyValueEntity(id).method(SpeakerProfileEntity::get).invoke();
    } catch (Exception e) {
      return HttpResponses.notFound("Speaker profile not found");
    }
    String newId = UUID.randomUUID().toString();
    SpeakerProfile copy =
        componentClient
            .forKeyValueEntity(newId)
            .method(SpeakerProfileEntity::create)
            .invoke(new SpeakerProfileEntity.Create(source.name() + " (copy)", source.description(), source.voiceModelId(), source.speakerNames(), Instant.now()));
    return HttpResponses.created(toApi(copy), "/api/speaker-profiles/" + newId);
  }

  @Get("/episode-profiles")
  public HttpResponse listEpisodeProfiles() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    EpisodeProfilesView.Entries entries =
        componentClient.forView().method(EpisodeProfilesView::all).invoke();
    return HttpResponses.ok(
        entries.items().stream()
            .map(
                e ->
                    new EpisodeProfileResponse(
                        e.id(), e.name(), e.description(), e.outlineModelId(), e.transcriptModelId(),
                        e.speakerProfileId(), e.defaultBriefing(), e.numSegments()))
            .toList());
  }

  @Post("/episode-profiles")
  public HttpResponse createEpisodeProfile(EpisodeProfileRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String id = UUID.randomUUID().toString();
    try {
      EpisodeProfile created =
          componentClient
              .forKeyValueEntity(id)
              .method(EpisodeProfileEntity::create)
              .invoke(
                  new EpisodeProfileEntity.Create(
                      request.name(), request.description(), request.outline_model_id(), request.transcript_model_id(),
                      request.speaker_profile_id(), request.default_briefing(), request.num_segments(), Instant.now()));
      return HttpResponses.created(toApi(created), "/api/episode-profiles/" + id);
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Get("/episode-profiles/{id}")
  public HttpResponse getEpisodeProfile(String id) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      return HttpResponses.ok(toApi(componentClient.forKeyValueEntity(id).method(EpisodeProfileEntity::get).invoke()));
    } catch (Exception e) {
      return HttpResponses.notFound("Episode profile not found");
    }
  }

  @Put("/episode-profiles/{id}")
  public HttpResponse updateEpisodeProfile(String id, EpisodeProfileRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forKeyValueEntity(id).method(EpisodeProfileEntity::delete).invoke();
    } catch (Exception ignored) {
    }
    try {
      EpisodeProfile updated =
          componentClient
              .forKeyValueEntity(id)
              .method(EpisodeProfileEntity::create)
              .invoke(
                  new EpisodeProfileEntity.Create(
                      request.name(), request.description(), request.outline_model_id(), request.transcript_model_id(),
                      request.speaker_profile_id(), request.default_briefing(), request.num_segments(), Instant.now()));
      return HttpResponses.ok(toApi(updated));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Delete("/episode-profiles/{id}")
  public HttpResponse deleteEpisodeProfile(String id) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient.forKeyValueEntity(id).method(EpisodeProfileEntity::delete).invoke();
      return HttpResponses.ok();
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Post("/episode-profiles/{id}/duplicate")
  public HttpResponse duplicateEpisodeProfile(String id) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    EpisodeProfile source;
    try {
      source = componentClient.forKeyValueEntity(id).method(EpisodeProfileEntity::get).invoke();
    } catch (Exception e) {
      return HttpResponses.notFound("Episode profile not found");
    }
    String newId = UUID.randomUUID().toString();
    EpisodeProfile copy =
        componentClient
            .forKeyValueEntity(newId)
            .method(EpisodeProfileEntity::create)
            .invoke(
                new EpisodeProfileEntity.Create(
                    source.name() + " (copy)", source.description(), source.outlineModelId(), source.transcriptModelId(),
                    source.speakerProfileId(), source.defaultBriefing(), source.numSegments(), Instant.now()));
    return HttpResponses.created(toApi(copy), "/api/episode-profiles/" + newId);
  }

  @Get("/podcasts/episodes")
  public HttpResponse listEpisodes() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    PodcastEpisodesView.Entries entries =
        componentClient.forView().method(PodcastEpisodesView::all).invoke();
    return HttpResponses.ok(entries.items().stream().map(this::toApi).toList());
  }

  @Get("/podcasts/episodes/{episodeId}")
  public HttpResponse getEpisode(String episodeId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    PodcastEpisode episode = fetch(episodeId);
    if (episode == null) return HttpResponses.notFound("Episode not found");
    return HttpResponses.ok(toApi(episode));
  }

  /** The source's own {@code audio_url} design: a separate binary route rather than base64
   * embedded in every episode's JSON body. */
  @Get("/podcasts/episodes/{episodeId}/audio")
  public HttpResponse getEpisodeAudio(String episodeId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    PodcastEpisode episode = fetch(episodeId);
    if (episode == null || episode.audioBase64() == null) {
      return HttpResponses.notFound("Episode has no audio file");
    }
    byte[] audio = Base64.getDecoder().decode(episode.audioBase64());
    return HttpResponses.of(
        akka.http.javadsl.model.StatusCodes.OK, MediaTypes.AUDIO_MPEG.toContentType(), audio);
  }

  @Post("/podcasts/generate")
  public HttpResponse generate(GenerateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String episodeId = UUID.randomUUID().toString();
    String briefing = request.briefing_suffix();
    if (briefing == null || briefing.isBlank()) {
      try {
        EpisodeProfile profile = componentClient.forKeyValueEntity(request.episode_profile()).method(EpisodeProfileEntity::get).invoke();
        briefing = profile.defaultBriefing();
      } catch (Exception e) {
        return HttpResponses.badRequest("Episode profile not found");
      }
    }
    componentClient
        .forWorkflow(episodeId)
        .method(PodcastGenerationWorkflow::start)
        .invoke(new PodcastGenerationWorkflow.Start(episodeId, request.notebook_id(), request.episode_profile(), request.episode_name(), briefing));
    return HttpResponses.ok(new GenerateResponse(episodeId, "pending", "Podcast generation started", request.episode_profile(), request.episode_name()));
  }

  @Delete("/podcasts/episodes/{episodeId}")
  public HttpResponse deleteEpisode(String episodeId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient
          .forEventSourcedEntity(episodeId)
          .method(PodcastEpisodeEntity::delete)
          .invoke(new PodcastEpisodeEntity.Deleted(Instant.now()));
    } catch (Exception ignored) {
      // Deleting an episode that doesn't exist is a no-op, matching every other delete route.
    }
    return HttpResponses.ok();
  }

  @Post("/podcasts/episodes/{episodeId}/retry")
  public HttpResponse retryEpisode(String episodeId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    PodcastEpisode episode = fetch(episodeId);
    if (episode == null) return HttpResponses.notFound("Episode not found");
    componentClient
        .forWorkflow(episodeId + "-retry-" + UUID.randomUUID())
        .method(PodcastGenerationWorkflow::start)
        .invoke(new PodcastGenerationWorkflow.Start(episodeId, episode.notebookId(), episode.episodeProfileId(), episode.name(), episode.briefing()));
    return HttpResponses.ok(new GenerateResponse(episodeId, "pending", "Retrying", episode.episodeProfileId(), episode.name()));
  }

  private PodcastEpisode fetch(String episodeId) {
    try {
      return componentClient.forEventSourcedEntity(episodeId).method(PodcastEpisodeEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }

  private SpeakerProfileResponse toApi(SpeakerProfile p) {
    return new SpeakerProfileResponse(p.id(), p.name(), p.description(), p.voiceModelId(), p.speakerNames());
  }

  private EpisodeProfileResponse toApi(EpisodeProfile p) {
    return new EpisodeProfileResponse(
        p.id(), p.name(), p.description(), p.outlineModelId(), p.transcriptModelId(), p.speakerProfileId(), p.defaultBriefing(), p.numSegments());
  }

  private EpisodeResponse toApi(PodcastEpisode e) {
    return new EpisodeResponse(
        e.id(), e.name(), e.episodeProfileId(), null, e.briefing(), e.audioBase64(),
        audioUrl(e.id(), e.audioBase64()), e.transcript(), e.outline(),
        e.createdAt() == null ? null : e.createdAt().toString(), e.status().name().toLowerCase(), e.errorMessage());
  }

  private EpisodeResponse toApi(PodcastEpisodesView.Entry e) {
    return new EpisodeResponse(
        e.id(), e.name(), e.episodeProfileId(), null, e.briefing(), e.audioBase64().orElse(null),
        audioUrl(e.id(), e.audioBase64().orElse(null)), e.transcript().orElse(null), e.outline().orElse(null),
        e.createdAt() == null ? null : e.createdAt().toString(), e.status().toLowerCase(), e.errorMessage().orElse(null));
  }

  private static String audioUrl(String episodeId, String audioBase64) {
    return audioBase64 == null ? null : "/api/podcasts/episodes/" + episodeId + "/audio";
  }
}
