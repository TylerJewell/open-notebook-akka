package io.akka.opennotebook.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.opennotebook.domain.PodcastEpisode;
import io.akka.opennotebook.domain.PodcastStatus;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** Outline → transcript → audio, end to end, against the mock provider's three AI shapes. */
class PodcastEndpointIntegrationTest extends TestKitSupport {

  private static final String MOCK_PROVIDER_URL = "http://127.0.0.1:26510";

  @Test
  void generatingAnEpisodeProducesAudioFromOutlineAndTranscript() {
    var chatCredential =
        httpClient
            .POST("/credentials")
            .withRequestBody(
                new CredentialEndpoint.CreateRequest(
                    "Mock chat", "openai", List.of("language"), "sk-1", MOCK_PROVIDER_URL))
            .responseBodyAs(CredentialEndpoint.CredentialResponse.class)
            .invoke()
            .body();
    var chatModel =
        httpClient
            .POST("/models")
            .withRequestBody(
                new ModelEndpoint.CreateModelRequest("mock-chat", "openai", "language", chatCredential.id()))
            .responseBodyAs(ModelEndpoint.ModelResponse.class)
            .invoke()
            .body();

    var ttsCredential =
        httpClient
            .POST("/credentials")
            .withRequestBody(
                new CredentialEndpoint.CreateRequest(
                    "Mock tts", "openai", List.of("text_to_speech"), "sk-2", MOCK_PROVIDER_URL))
            .responseBodyAs(CredentialEndpoint.CredentialResponse.class)
            .invoke()
            .body();
    var ttsModel =
        httpClient
            .POST("/models")
            .withRequestBody(
                new ModelEndpoint.CreateModelRequest("mock-tts", "openai", "text_to_speech", ttsCredential.id()))
            .responseBodyAs(ModelEndpoint.ModelResponse.class)
            .invoke()
            .body();

    var speakerProfile =
        httpClient
            .POST("/speaker-profiles")
            .withRequestBody(
                new PodcastEndpoint.CreateSpeakerProfileRequest(
                    "Narrator", "Single narrator voice", ttsModel.id(), List.of("Narrator")))
            .responseBodyAs(io.akka.opennotebook.domain.SpeakerProfile.class)
            .invoke()
            .body();

    var episodeProfile =
        httpClient
            .POST("/episode-profiles")
            .withRequestBody(
                new PodcastEndpoint.CreateEpisodeProfileRequest(
                    "Standard", "d", chatModel.id(), chatModel.id(), speakerProfile.id(), "Summarize the notebook", 3))
            .responseBodyAs(io.akka.opennotebook.domain.EpisodeProfile.class)
            .invoke()
            .body();

    var notebook =
        httpClient
            .POST("/notebooks")
            .withRequestBody(new NotebookEndpoint.CreateRequest("NB", "desc"))
            .responseBodyAs(NotebookEndpoint.NotebookResponse.class)
            .invoke()
            .body();

    var episode =
        httpClient
            .POST("/podcasts/episodes")
            .withRequestBody(
                new PodcastEndpoint.GenerateEpisodeRequest(notebook.notebookId(), episodeProfile.id(), "Ep1", null))
            .responseBodyAs(PodcastEpisode.class)
            .invoke()
            .body();

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              var fetched =
                  httpClient
                      .GET("/podcasts/episodes/" + episode.id())
                      .responseBodyAs(PodcastEpisode.class)
                      .invoke()
                      .body();
              assertThat(fetched.status()).isEqualTo(PodcastStatus.COMPLETED);
              assertThat(fetched.outline()).isNotBlank();
              assertThat(fetched.transcript()).isNotBlank();
              assertThat(fetched.audioBase64()).isNotBlank();
            });
  }
}
