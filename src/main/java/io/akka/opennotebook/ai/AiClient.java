package io.akka.opennotebook.ai;

import akka.javasdk.client.ComponentClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.opennotebook.application.CredentialEntity;
import io.akka.opennotebook.application.ModelEntity;
import io.akka.opennotebook.domain.Credential;
import io.akka.opennotebook.domain.ModelRecord;
import io.akka.opennotebook.exceptions.ConfigurationError;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Provisions and calls a language/embedding/text-to-speech model (SPEC-001 §AI provisioning) —
 * the port's equivalent of the source's {@code provision_langchain_model} /
 * {@code ModelManager.get_model}, collapsed to a single HTTP shape.
 *
 * <p><b>Divergence, recorded as an assumed decision (see port-log):</b> the source normalizes 18+
 * provider SDKs behind Esperanto's {@code AIFactory}. This port normalizes them one level lower —
 * as a single OpenAI-compatible HTTP client (Bearer auth, {@code /v1/chat/completions},
 * {@code /v1/embeddings}, {@code /v1/audio/speech}) — the same shape Ollama, LM Studio, OpenRouter,
 * Groq, DeepSeek, Together, and this port's own test double ({@code probes/mock_provider.py})
 * already speak, and the shape a {@link Credential#baseUrl()} override selects exactly the way
 * the source's own {@code base_url} field does for self-hosted providers. Providers whose native
 * protocol is not OpenAI-compatible (Anthropic's Messages API, Vertex, Bedrock) are reachable only
 * through a compatibility endpoint the caller configures via {@code baseUrl}, not through a
 * bespoke per-SDK integration — this is a narrower rebuild of the provider-abstraction *capability*
 * (one HTTP shape, not eighteen SDKs), not an excluded capability.
 */
public class AiClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final java.net.http.HttpClient HTTP =
      java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private static final Map<String, String> DEFAULT_BASE_URL =
      Map.of(
          "openai", "https://api.openai.com",
          "groq", "https://api.groq.com/openai",
          "deepseek", "https://api.deepseek.com",
          "together", "https://api.together.xyz",
          "openrouter", "https://openrouter.ai/api",
          "mistral", "https://api.mistral.ai");

  private final ComponentClient componentClient;

  public AiClient(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record ChatMessage(String role, String content) {}

  public ModelRecord resolveModel(String modelId) {
    if (modelId == null || modelId.isBlank()) {
      throw new ConfigurationError("No model configured");
    }
    ModelRecord model = componentClient.forKeyValueEntity(modelId).method(ModelEntity::get).invoke();
    if (!model.exists()) {
      throw new ConfigurationError("Model with ID " + modelId + " not found");
    }
    return model;
  }

  private Credential resolveCredential(String credentialId) {
    if (credentialId == null || credentialId.isBlank()) return null;
    Credential credential =
        componentClient.forKeyValueEntity(credentialId).method(CredentialEntity::get).invoke();
    return credential.exists() ? credential : null;
  }

  private String baseUrlFor(ModelRecord model, Credential credential) {
    if (credential != null && credential.baseUrl() != null && !credential.baseUrl().isBlank()) {
      return credential.baseUrl();
    }
    String fallback = DEFAULT_BASE_URL.get(model.provider() == null ? "" : model.provider().toLowerCase());
    if (fallback == null) {
      throw new ConfigurationError(
          "No base URL configured for provider '" + model.provider() + "'. "
              + "Set a credential with baseUrl, or use a known provider name.");
    }
    return fallback;
  }

  private String apiKeyFor(Credential credential) {
    if (credential == null || credential.encryptedApiKey() == null) return null;
    return EncryptionUtil.decrypt(credential.encryptedApiKey());
  }

  private HttpRequest.Builder authedRequest(String url, Credential credential) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
    String apiKey = apiKeyFor(credential);
    if (apiKey != null && !apiKey.isBlank()) {
      builder.header("Authorization", "Bearer " + apiKey);
    }
    return builder;
  }

  /** {@code provision_langchain_model} + a chat completion call, collapsed into one round trip. */
  public String chatComplete(String modelId, String systemPrompt, List<ChatMessage> history) {
    ModelRecord model = resolveModel(modelId);
    if (!ModelRecord.TYPE_LANGUAGE.equals(model.type())) {
      throw new ConfigurationError("Model " + modelId + " is not a language model");
    }
    Credential credential = resolveCredential(model.credentialId());
    String url = baseUrlFor(model, credential) + "/v1/chat/completions";

    ArrayNode messages = MAPPER.createArrayNode();
    if (systemPrompt != null && !systemPrompt.isBlank()) {
      messages.add(MAPPER.createObjectNode().put("role", "system").put("content", systemPrompt));
    }
    for (ChatMessage m : history) {
      messages.add(MAPPER.createObjectNode().put("role", m.role()).put("content", m.content()));
    }
    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", model.name());
    body.set("messages", messages);

    try {
      HttpRequest request =
          authedRequest(url, credential)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        throw new ConfigurationError(
            "Chat completion call to " + model.provider() + " failed: HTTP " + response.statusCode());
      }
      JsonNode root = MAPPER.readTree(response.body());
      return root.path("choices").path(0).path("message").path("content").asText();
    } catch (ConfigurationError e) {
      throw e;
    } catch (Exception e) {
      throw new ConfigurationError("Chat completion call failed: " + e.getMessage());
    }
  }

  public double[] embed(String modelId, String text) {
    ModelRecord model = resolveModel(modelId);
    if (!ModelRecord.TYPE_EMBEDDING.equals(model.type())) {
      throw new ConfigurationError("Model " + modelId + " is not an embedding model");
    }
    Credential credential = resolveCredential(model.credentialId());
    String url = baseUrlFor(model, credential) + "/v1/embeddings";

    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", model.name());
    body.put("input", text);

    try {
      HttpRequest request =
          authedRequest(url, credential)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        throw new ConfigurationError(
            "Embedding call to " + model.provider() + " failed: HTTP " + response.statusCode());
      }
      JsonNode vector = MAPPER.readTree(response.body()).path("data").path(0).path("embedding");
      double[] result = new double[vector.size()];
      for (int i = 0; i < vector.size(); i++) result[i] = vector.get(i).asDouble();
      return result;
    } catch (ConfigurationError e) {
      throw e;
    } catch (Exception e) {
      throw new ConfigurationError("Embedding call failed: " + e.getMessage());
    }
  }

  public byte[] textToSpeech(String modelId, String text, String voice) {
    ModelRecord model = resolveModel(modelId);
    if (!ModelRecord.TYPE_TEXT_TO_SPEECH.equals(model.type())) {
      throw new ConfigurationError("Model " + modelId + " is not a text-to-speech model");
    }
    Credential credential = resolveCredential(model.credentialId());
    String url = baseUrlFor(model, credential) + "/v1/audio/speech";

    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", model.name());
    body.put("input", text);
    if (voice != null) body.put("voice", voice);

    try {
      HttpRequest request =
          authedRequest(url, credential)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() >= 300) {
        throw new ConfigurationError(
            "Text-to-speech call to " + model.provider() + " failed: HTTP " + response.statusCode());
      }
      return response.body();
    } catch (ConfigurationError e) {
      throw e;
    } catch (Exception e) {
      throw new ConfigurationError("Text-to-speech call failed: " + e.getMessage());
    }
  }

  public static String base64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }
}
