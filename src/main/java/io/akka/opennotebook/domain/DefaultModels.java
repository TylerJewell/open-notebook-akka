package io.akka.opennotebook.domain;

/**
 * The server-wide default model choice per purpose (SPEC-001 §Models) — mirrors the source's
 * singleton {@code open_notebook:default_models} record. Each field is a {@link ModelRecord} id,
 * or null when nothing is configured for that purpose yet.
 */
public record DefaultModels(
    String defaultChatModel,
    String defaultTransformationModel,
    String largeContextModel,
    String defaultTextToSpeechModel,
    String defaultSpeechToTextModel,
    String defaultEmbeddingModel,
    String defaultToolsModel) {

  public static DefaultModels empty() {
    return new DefaultModels(null, null, null, null, null, null, null);
  }
}
