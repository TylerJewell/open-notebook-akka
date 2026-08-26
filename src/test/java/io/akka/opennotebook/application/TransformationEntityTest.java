package io.akka.opennotebook.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.opennotebook.domain.Transformation;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TransformationEntityTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static KeyValueEntityTestKit<Transformation, TransformationEntity> kit(String id) {
    return KeyValueEntityTestKit.of(id, TransformationEntity::new);
  }

  @Test
  void createRejectsEmptyPrompt() {
    var testKit = kit("t1");
    var result =
        testKit
            .method(TransformationEntity::create)
            .invoke(new TransformationEntity.Create("summarize", "Summary", "d", "", false, null, T0));
    assertThat(result.isError()).isTrue();
  }

  @Test
  void createStoresTransformation() {
    var testKit = kit("t1");
    var result =
        testKit
            .method(TransformationEntity::create)
            .invoke(
                new TransformationEntity.Create(
                    "summarize", "Summary", "d", "Summarize this", true, null, T0));
    assertThat(result.isError()).isFalse();
    assertThat(testKit.getState().title()).isEqualTo("Summary");
    assertThat(testKit.getState().applyDefault()).isTrue();
  }
}
