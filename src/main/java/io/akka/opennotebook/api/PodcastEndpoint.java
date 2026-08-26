package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
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

/** Speaker/episode profiles and generated episodes (SPEC-001 §Podcasts). */
@HttpEndpoint("")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class PodcastEndpoint extends AbstractHttpEndpoint {

  public record CreateSpeakerProfileRequest(String name, String description, String voiceModelId, List<String> speakerNames) {}

  public record CreateEpisodeProfileRequest(
      String name,
      String description,
      String outlineModelId,
      String transcriptModelId,
      String speakerProfileId,
      String defaultBriefing,
      int numSegments) {}

  public record GenerateEpisodeRequest(String notebookId, String episodeProfileId, String name, String briefing) {}

  private final ComponentClient componentClient;

  public PodcastEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/speaker-profiles")
  public HttpResponse createSpeakerProfile(CreateSpeakerProfileRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String id = UUID.randomUUID().toString();
    try {
      SpeakerProfile created =
          componentClient
              .forKeyValueEntity(id)
              .method(SpeakerProfileEntity::create)
              .invoke(
                  new SpeakerProfileEntity.Create(
                      request.name(), request.description(), request.voiceModelId(), request.speakerNames(),
                      Instant.now()));
      return HttpResponses.created(created, "/speaker-profiles/" + id);
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Get("/speaker-profiles/{id}")
  public HttpResponse getSpeakerProfile(String id) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      SpeakerProfile p = componentClient.forKeyValueEntity(id).method(SpeakerProfileEntity::get).invoke();
      return HttpResponses.ok(p);
    } catch (Exception e) {
      return HttpResponses.notFound("Speaker profile not found");
    }
  }

  @Post("/episode-profiles")
  public HttpResponse createEpisodeProfile(CreateEpisodeProfileRequest request) {
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
                      request.name(), request.description(), request.outlineModelId(), request.transcriptModelId(),
                      request.speakerProfileId(), request.defaultBriefing(), request.numSegments(), Instant.now()));
      return HttpResponses.created(created, "/episode-profiles/" + id);
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
  }

  @Get("/episode-profiles/{id}")
  public HttpResponse getEpisodeProfile(String id) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      EpisodeProfile p = componentClient.forKeyValueEntity(id).method(EpisodeProfileEntity::get).invoke();
      return HttpResponses.ok(p);
    } catch (Exception e) {
      return HttpResponses.notFound("Episode profile not found");
    }
  }

  @Post("/podcasts/episodes")
  public HttpResponse generateEpisode(GenerateEpisodeRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String episodeId = UUID.randomUUID().toString();
    String briefing = request.briefing();
    if (briefing == null || briefing.isBlank()) {
      try {
        EpisodeProfile profile =
            componentClient
                .forKeyValueEntity(request.episodeProfileId())
                .method(EpisodeProfileEntity::get)
                .invoke();
        briefing = profile.defaultBriefing();
      } catch (Exception e) {
        return HttpResponses.badRequest("Episode profile not found");
      }
    }
    componentClient
        .forWorkflow(episodeId)
        .method(PodcastGenerationWorkflow::start)
        .invoke(
            new PodcastGenerationWorkflow.Start(
                episodeId, request.notebookId(), request.episodeProfileId(), request.name(), briefing));
    return HttpResponses.created(fetch(episodeId), "/podcasts/episodes/" + episodeId);
  }

  @Get("/podcasts/episodes/{episodeId}")
  public HttpResponse getEpisode(String episodeId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    PodcastEpisode episode = fetch(episodeId);
    if (episode == null) {
      return HttpResponses.notFound("Episode not found");
    }
    return HttpResponses.ok(episode);
  }

  private PodcastEpisode fetch(String episodeId) {
    try {
      return componentClient.forEventSourcedEntity(episodeId).method(PodcastEpisodeEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }
}
