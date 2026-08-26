package io.akka.opennotebook.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.opennotebook.domain.Credential;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CredentialEntityTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static KeyValueEntityTestKit<Credential, CredentialEntity> kit(String id) {
    return KeyValueEntityTestKit.of(id, CredentialEntity::new);
  }

  @Test
  void createRejectsEmptyName() {
    var testKit = kit("c1");
    var result =
        testKit
            .method(CredentialEntity::create)
            .invoke(new CredentialEntity.Create("", "openai", List.of("language"), "sk-x", null, T0));
    assertThat(result.isError()).isTrue();
  }

  @Test
  void createEncryptsApiKeyAtRest() {
    var testKit = kit("c1");
    var result =
        testKit
            .method(CredentialEntity::create)
            .invoke(
                new CredentialEntity.Create(
                    "Prod", "openai", List.of("language", "embedding"), "sk-secret-value", null, T0));
    assertThat(result.isError()).isFalse();
    Credential state = testKit.getState();
    assertThat(state.exists()).isTrue();
    assertThat(state.name()).isEqualTo("Prod");
    // The stored value is ciphertext, never the plaintext key.
    assertThat(state.encryptedApiKey()).isNotNull().doesNotContain("sk-secret-value");
  }

  @Test
  void updateWithoutApiKeyKeepsExistingKey() {
    var testKit = kit("c1");
    testKit
        .method(CredentialEntity::create)
        .invoke(new CredentialEntity.Create("Prod", "openai", List.of("language"), "sk-1", null, T0));
    String before = testKit.getState().encryptedApiKey();
    testKit.method(CredentialEntity::update).invoke(new CredentialEntity.Update("Prod v2", null, null, null));
    assertThat(testKit.getState().name()).isEqualTo("Prod v2");
    assertThat(testKit.getState().encryptedApiKey()).isEqualTo(before);
  }

  @Test
  void deleteRemovesCredential() {
    var testKit = kit("c1");
    testKit
        .method(CredentialEntity::create)
        .invoke(new CredentialEntity.Create("Prod", "openai", List.of("language"), "sk-1", null, T0));
    testKit.method(CredentialEntity::delete).invoke();
    assertThat(testKit.isDeleted()).isTrue();
  }

  @Test
  void getOnMissingCredentialErrors() {
    var testKit = kit("missing");
    var result = testKit.method(CredentialEntity::get).invoke();
    assertThat(result.isError()).isTrue();
  }
}
