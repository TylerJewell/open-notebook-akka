package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
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
import io.akka.opennotebook.application.PodcastEpisodeEntity;
import io.akka.opennotebook.application.PodcastGenerationWorkflow;
import io.akka.opennotebook.application.SpeakerProfileEntity;
import io.akka.opennotebook.domain.EpisodeProfile;
import io.akka.opennotebook.domain.PodcastEpisode;
import io.akka.opennotebook.domain.SpeakerProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The frontend's {@code frontend/src/lib/api/podcasts.ts} against {@link PodcastEpisodeEntity} /
 * {@link EpisodeProfileEntity} / {@link SpeakerProfileEntity} / {@link PodcastGenerationWorkflow}
 * -- R26, snake_case wire shape, plus the list/update/duplicate/retry routes the bare-path {@code
 * PodcastEndpoint} never needed.
 *
 * <p><b>Narrowed:</b> {@code listEpisodes} has no backing list view (episodes were always
 * addressed one at a time, the same gap SS1's out-of-scope list closes for notebooks/sources/
 * notes) -- adding one here would need a fourth {@code *View} class for a capability this port
 * already narrows in three other ways (SS10 D-10 single-voice synthesis, D-11 Workflow-not-queue).
 * Declared rather than silently stubbed: returns the single most-recently-created episode this
 * server session has generated, tracked in memory, not the full history a real list view would
 * back. audio is returned as base64 in the JSON body (matching the bare-path {@code
 * PodcastEndpoint}) rather than a separate download URL, since no binary/file-serving route
 * exists in this port.
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
      String audio_file, String transcript, String outline, String created, String job_status, String error_message) {}

  private final ComponentClient componentClient;
  private static volatile String lastEpisodeId;

  public ApiPodcastEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/speaker-profiles")
  public HttpResponse listSpeakerProfiles() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    return HttpResponses.ok(List.of()); // No list view -- see class doc's episode-list narrowing.
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
    return HttpResponses.ok(List.of()); // No list view -- see class doc.
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
    String id = lastEpisodeId;
    if (id == null) return HttpResponses.ok(List.of());
    PodcastEpisode episode = fetch(id);
    return HttpResponses.ok(episode == null ? List.of() : List.of(toApi(episode)));
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
    lastEpisodeId = episodeId;
    return HttpResponses.ok(new GenerateResponse(episodeId, "pending", "Podcast generation started", request.episode_profile(), request.episode_name()));
  }

  @Delete("/podcasts/episodes/{episodeId}")
  public HttpResponse deleteEpisode(String episodeId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    if (episodeId.equals(lastEpisodeId)) lastEpisodeId = null;
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
        e.id(), e.name(), e.episodeProfileId(), null, e.briefing(), e.audioBase64(), e.transcript(), e.outline(),
        e.createdAt() == null ? null : e.createdAt().toString(), e.status().name().toLowerCase(), e.errorMessage());
  }
}
