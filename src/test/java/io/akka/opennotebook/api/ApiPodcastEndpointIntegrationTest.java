package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** The frontend's {@code podcasts.ts} against the real {@link
 * io.akka.opennotebook.application.PodcastEpisodesView}/{@link
 * io.akka.opennotebook.application.SpeakerProfilesView}/{@link
 * io.akka.opennotebook.application.EpisodeProfilesView} list views and the binary audio route --
 * the three gaps closed after the bare-path {@code PodcastEndpointIntegrationTest} was written
 * (an in-memory "last episode" list, base64-only audio, and a delete that didn't delete). */
class ApiPodcastEndpointIntegrationTest extends TestKitSupport {

  private static final String MOCK_PROVIDER_URL = "http://127.0.0.1:26510";

  @Test
  void listedEpisodePersistsWithRealAudioUrlAndDisappearsAfterDelete() {
    var chatCredential =
        httpClient
            .POST("/credentials")
            .withRequestBody(
                new CredentialEndpoint.CreateRequest("Mock chat", "openai", List.of("language"), "sk-1", MOCK_PROVIDER_URL))
            .responseBodyAs(CredentialEndpoint.CredentialResponse.class)
            .invoke()
            .body();
    var chatModel =
        httpClient
            .POST("/models")
            .withRequestBody(new ModelEndpoint.CreateModelRequest("mock-chat", "openai", "language", chatCredential.id()))
            .responseBodyAs(ModelEndpoint.ModelResponse.class)
            .invoke()
            .body();
    var ttsCredential =
        httpClient
            .POST("/credentials")
            .withRequestBody(
                new CredentialEndpoint.CreateRequest("Mock tts", "openai", List.of("text_to_speech"), "sk-2", MOCK_PROVIDER_URL))
            .responseBodyAs(CredentialEndpoint.CredentialResponse.class)
            .invoke()
            .body();
    var ttsModel =
        httpClient
            .POST("/models")
            .withRequestBody(new ModelEndpoint.CreateModelRequest("mock-tts", "openai", "text_to_speech", ttsCredential.id()))
            .responseBodyAs(ModelEndpoint.ModelResponse.class)
            .invoke()
            .body();

    var speakerProfile =
        httpClient
            .POST("/api/speaker-profiles")
            .withRequestBody(new ApiPodcastEndpoint.SpeakerProfileRequest("Narrator", "d", ttsModel.id(), List.of("Narrator")))
            .responseBodyAs(ApiPodcastEndpoint.SpeakerProfileResponse.class)
            .invoke()
            .body();

    // The list view this replaces the in-memory hack with -- eventually consistent, like every
    // other View in this port.
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(httpClient.GET("/api/speaker-profiles").responseBodyAs(List.class).invoke().body())
                    .hasSize(1));

    var episodeProfile =
        httpClient
            .POST("/api/episode-profiles")
            .withRequestBody(
                new ApiPodcastEndpoint.EpisodeProfileRequest(
                    "Standard", "d", chatModel.id(), chatModel.id(), speakerProfile.id(), "Summarize", 3))
            .responseBodyAs(ApiPodcastEndpoint.EpisodeProfileResponse.class)
            .invoke()
            .body();
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(httpClient.GET("/api/episode-profiles").responseBodyAs(List.class).invoke().body())
                    .hasSize(1));

    var notebook =
        httpClient
            .POST("/api/notebooks")
            .withRequestBody(new ApiNotebookEndpoint.CreateRequest("NB", "desc"))
            .responseBodyAs(ApiNotebookEndpoint.NotebookResponse.class)
            .invoke()
            .body();

    var generated =
        httpClient
            .POST("/api/podcasts/generate")
            .withRequestBody(
                new ApiPodcastEndpoint.GenerateRequest(episodeProfile.id(), speakerProfile.id(), "Ep1", null, notebook.id(), null))
            .responseBodyAs(ApiPodcastEndpoint.GenerateResponse.class)
            .invoke()
            .body();

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              var fetched =
                  httpClient
                      .GET("/api/podcasts/episodes/" + generated.job_id())
                      .responseBodyAs(ApiPodcastEndpoint.EpisodeResponse.class)
                      .invoke()
                      .body();
              assertThat(fetched.job_status()).isEqualTo("completed");
              assertThat(fetched.audio_url()).isEqualTo("/api/podcasts/episodes/" + generated.job_id() + "/audio");
            });

    // The list view: every generated episode, not only the most recent (the narrowing this replaces).
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(httpClient.GET("/api/podcasts/episodes").responseBodyAs(List.class).invoke().body())
                    .hasSize(1));

    // The binary audio route, decoded from the same base64 the entity stores.
    var audioResponse = httpClient.GET("/api/podcasts/episodes/" + generated.job_id() + "/audio").invoke();
    assertThat(audioResponse.httpResponse().entity().getContentType().mediaType().toString()).isEqualTo("audio/mpeg");

    // Delete is real now, not a no-op over an in-memory pointer.
    httpClient.DELETE("/api/podcasts/episodes/" + generated.job_id()).invoke();
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(httpClient.GET("/api/podcasts/episodes").responseBodyAs(List.class).invoke().body())
                    .isEmpty());
  }
}
