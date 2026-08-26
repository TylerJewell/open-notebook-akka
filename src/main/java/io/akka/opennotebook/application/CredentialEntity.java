package io.akka.opennotebook.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;
import io.akka.opennotebook.domain.Credential;
import java.time.Instant;
import java.util.List;

/**
 * One provider account (SPEC-001 §Credentials). A key-value entity, not event-sourced: the
 * source's own {@code credential} table is a plain CRUD record with no state machine of its own
 * — only whether it exists, and its current fields.
 */
@Component(id = "credential")
public class CredentialEntity extends KeyValueEntity<Credential> {

  public record Create(
      String name, String provider, List<String> modalities, String apiKey, String baseUrl, Instant now) {}

  public record Update(String name, List<String> modalities, String apiKey, String baseUrl) {}

  private final String id;

  public CredentialEntity(KeyValueEntityContext context) {
    this.id = context.entityId();
  }

  @Override
  public Credential emptyState() {
    return Credential.empty();
  }

  public Effect<Credential> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Credential already exists");
    }
    if (command.name() == null || command.name().isBlank()) {
      return effects().error("Credential name cannot be empty");
    }
    if (command.provider() == null || command.provider().isBlank()) {
      return effects().error("Credential provider cannot be empty");
    }
    String encrypted =
        command.apiKey() == null || command.apiKey().isBlank()
            ? null
            : io.akka.opennotebook.ai.EncryptionUtil.encrypt(command.apiKey());
    Credential created =
        Credential.create(
            id,
            command.name(),
            command.provider(),
            command.modalities() == null ? List.of() : command.modalities(),
            encrypted,
            command.baseUrl(),
            command.now());
    return effects().updateState(created).thenReply(created);
  }

  public Effect<Credential> update(Update command) {
    if (!currentState().exists()) {
      return effects().error("Credential not found");
    }
    String encrypted =
        command.apiKey() == null || command.apiKey().isBlank()
            ? currentState().encryptedApiKey()
            : io.akka.opennotebook.ai.EncryptionUtil.encrypt(command.apiKey());
    Credential updated =
        new Credential(
            currentState().id(),
            command.name() == null ? currentState().name() : command.name(),
            currentState().provider(),
            command.modalities() == null ? currentState().modalities() : command.modalities(),
            encrypted,
            command.baseUrl() == null ? currentState().baseUrl() : command.baseUrl(),
            currentState().createdAt(),
            true);
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<String> delete() {
    if (!currentState().exists()) {
      return effects().error("Credential not found");
    }
    return effects().deleteEntity().thenReply("deleted");
  }

  public ReadOnlyEffect<Credential> get() {
    if (!currentState().exists()) {
      return effects().error("Credential not found");
    }
    return effects().reply(currentState());
  }
}
