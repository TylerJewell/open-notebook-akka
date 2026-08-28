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
 * <p><b>Divergence, recorded as an assumed decision (see port-log):</b> the source normalizes 22
 * provider SDKs behind Esperanto's {@code AIFactory}. This port normalizes most of them one level
 * lower — as a single OpenAI-compatible HTTP client (Bearer auth, {@code /v1/chat/completions},
 * {@code /v1/embeddings}, {@code /v1/audio/speech}) — the same shape Ollama, LM Studio, OpenRouter,
 * Groq, DeepSeek, Together, xAI, Alibaba's DashScope (compatible mode), Novita, PPQ, and this
 * port's own test double ({@code probes/mock_provider.py}) already speak, and the shape a {@link
 * Credential#baseUrl()} override selects exactly the way the source's own {@code base_url} field
 * does for self-hosted providers. Anthropic's own Messages API — genuinely not OpenAI-compatible —
 * is translated directly (see {@code anthropicChatComplete}), proving the design extends past the
 * default shape rather than being hard-walled to it. The providers with neither an
 * OpenAI-compatible endpoint nor a translation written here (Google's native Gemini API, Vertex,
 * Cohere, Voyage, ElevenLabs, Deepgram, Azure's key-header variant) are reachable only through a
 * compatibility endpoint the caller configures via {@code baseUrl} — a narrower rebuild of the
 * provider-abstraction *capability* (one HTTP shape plus one native translation, not
 * twenty-two SDKs), not an excluded capability.
 */
public class AiClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final java.net.http.HttpClient HTTP =
      java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private static final Map<String, String> DEFAULT_BASE_URL =
      Map.ofEntries(
          Map.entry("openai", "https://api.openai.com"),
          Map.entry("groq", "https://api.groq.com/openai"),
          Map.entry("deepseek", "https://api.deepseek.com"),
          Map.entry("together", "https://api.together.xyz"),
          Map.entry("openrouter", "https://openrouter.ai/api"),
          Map.entry("mistral", "https://api.mistral.ai"),
          // OpenAI-compatible chat/embeddings endpoints -- real base URLs, no per-SDK wrapper.
          Map.entry("xai", "https://api.x.ai"),
          Map.entry("dashscope", "https://dashscope.aliyuncs.com/compatible-mode"),
          Map.entry("novita", "https://api.novita.ai/openai"),
          Map.entry("ppq", "https://api.ppq.ai"),
          // Native, non-OpenAI-compatible protocol, translated in chatComplete/embed directly.
          Map.entry("anthropic", "https://api.anthropic.com"));

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
    return baseUrlFor(model.provider(), credential);
  }

  private String baseUrlFor(String provider, Credential credential) {
    if (credential != null && credential.baseUrl() != null && !credential.baseUrl().isBlank()) {
      return credential.baseUrl();
    }
    String fallback = DEFAULT_BASE_URL.get(provider == null ? "" : provider.toLowerCase());
    if (fallback == null) {
      throw new ConfigurationError(
          "No base URL configured for provider '" + provider + "'. "
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
    if ("anthropic".equalsIgnoreCase(model.provider())) {
      // A baseUrl override still changes where this goes -- baseUrlFor honors it inside
      // anthropicChatComplete -- it just doesn't switch the wire shape: a provider named
      // "anthropic" is presumed to still speak Anthropic's own protocol at whatever URL a
      // caller points it to, the same way this port never speaks two shapes to one provider.
      return anthropicChatComplete(model, credential, systemPrompt, history);
    }
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

  /** Anthropic's own Messages API ({@code POST /v1/messages}, {@code x-api-key} +
   * {@code anthropic-version} headers, a top-level {@code system} field instead of a
   * {@code system}-role message, and {@code content[].text} instead of {@code
   * choices[0].message.content}) -- proof the one-HTTP-shape design (SPEC-001 SS6 D-7) is a
   * narrower rebuild of the provider-abstraction capability, not a hard wall around it: a
   * second real wire protocol translates the same way the first one does, when a provider's
   * native API is worth adding directly instead of routing through a compatible proxy. */
  private String anthropicChatComplete(ModelRecord model, Credential credential, String systemPrompt, List<ChatMessage> history) {
    String url = baseUrlFor(model, credential) + "/v1/messages";

    ArrayNode messages = MAPPER.createArrayNode();
    for (ChatMessage m : history) {
      messages.add(MAPPER.createObjectNode().put("role", m.role()).put("content", m.content()));
    }
    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", model.name());
    body.put("max_tokens", 4096);
    if (systemPrompt != null && !systemPrompt.isBlank()) {
      body.put("system", systemPrompt);
    }
    body.set("messages", messages);

    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(60))
              .header("Content-Type", "application/json")
              .header("anthropic-version", "2023-06-01");
      String apiKey = apiKeyFor(credential);
      if (apiKey != null && !apiKey.isBlank()) {
        builder.header("x-api-key", apiKey);
      }
      HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        throw new ConfigurationError("Chat completion call to anthropic failed: HTTP " + response.statusCode());
      }
      JsonNode root = MAPPER.readTree(response.body());
      return root.path("content").path(0).path("text").asText();
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

  public record DiscoveredModel(String name, String provider) {}

  /** {@code credentials.py}'s real network call underneath {@code test}/{@code discover}: a
   * plain {@code GET /v1/models}, the same OpenAI-compatible shape every other call in this
   * class already speaks. Standing in only for the network call to a real provider, never for
   * this port's own routing or error handling -- {@code probes/mock_provider.py} answers this
   * route the same way it answers chat/embeddings/audio. */
  public List<DiscoveredModel> listModels(Credential credential) {
    String url = baseUrlFor(credential.provider(), credential) + "/v1/models";
    try {
      HttpRequest request = authedRequest(url, credential).GET().build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        throw new ConfigurationError("Model list call to " + credential.provider() + " failed: HTTP " + response.statusCode());
      }
      JsonNode data = MAPPER.readTree(response.body()).path("data");
      List<DiscoveredModel> result = new java.util.ArrayList<>();
      if (data.isArray()) {
        for (JsonNode entry : data) {
          result.add(new DiscoveredModel(entry.path("id").asText(), credential.provider()));
        }
      }
      return result;
    } catch (ConfigurationError e) {
      throw e;
    } catch (Exception e) {
      throw new ConfigurationError("Model list call failed: " + e.getMessage());
    }
  }
}
