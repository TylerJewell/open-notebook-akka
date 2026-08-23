package io.akka.opennotebook.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExtractionTest {

  @Test
  void plainTextPassesThroughVerbatim() {
    var outcome = Extraction.plainText("The quick brown fox jumps over the lazy dog.");
    assertThat(outcome).isInstanceOf(Extraction.Outcome.Success.class);
    var success = (Extraction.Outcome.Success) outcome;
    assertThat(success.content()).isEqualTo("The quick brown fox jumps over the lazy dog.");
    assertThat(success.title()).isNull();
  }

  @Test
  void emptyContentIsPermanentFailure() {
    assertThat(Extraction.plainText("")).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
    assertThat(Extraction.plainText("   ")).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
    assertThat(Extraction.plainText(null)).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
  }

  @Test
  void htmlIsReducedToTitleAndVisibleText() {
    String html =
        """
        <html><head><title>Example Domain</title><style>body{color:red}</style></head>
        <body><script>track()</script><h1>Example Domain</h1>
        <p>This domain is for use in documentation examples.</p></body></html>
        """;
    var outcome = Extraction.fromFetchedHtml(html);
    assertThat(outcome).isInstanceOf(Extraction.Outcome.Success.class);
    var success = (Extraction.Outcome.Success) outcome;
    assertThat(success.title()).isEqualTo("Example Domain");
    assertThat(success.content())
        .contains("Example Domain")
        .contains("This domain is for use in documentation examples.")
        .doesNotContain("track()")
        .doesNotContain("<h1>");
  }

  @Test
  void emptyHtmlIsPermanentFailure() {
    assertThat(Extraction.fromFetchedHtml("")).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
    assertThat(Extraction.fromFetchedHtml("<html><body></body></html>"))
        .isInstanceOf(Extraction.Outcome.PermanentFailure.class);
  }
}
