package io.akka.opennotebook.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.opennotebook.domain.ModelRecord;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ModelEntityTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static KeyValueEntityTestKit<ModelRecord, ModelEntity> kit(String id) {
    return KeyValueEntityTestKit.of(id, ModelEntity::new);
  }

  @Test
  void createRejectsInvalidType() {
    var testKit = kit("m1");
    var result =
        testKit.method(ModelEntity::create).invoke(new ModelEntity.Create("gpt-4o", "openai", "vision", null, T0));
    assertThat(result.isError()).isTrue();
  }

  @Test
  void createStoresLanguageModel() {
    var testKit = kit("m1");
    var result =
        testKit
            .method(ModelEntity::create)
            .invoke(new ModelEntity.Create("gpt-4o", "openai", ModelRecord.TYPE_LANGUAGE, "cred1", T0));
    assertThat(result.isError()).isFalse();
    assertThat(testKit.getState().name()).isEqualTo("gpt-4o");
    assertThat(testKit.getState().credentialId()).isEqualTo("cred1");
  }

  @Test
  void deleteRemovesModel() {
    var testKit = kit("m1");
    testKit
        .method(ModelEntity::create)
        .invoke(new ModelEntity.Create("gpt-4o", "openai", ModelRecord.TYPE_LANGUAGE, "cred1", T0));
    testKit.method(ModelEntity::delete).invoke();
    assertThat(testKit.isDeleted()).isTrue();
  }
}
