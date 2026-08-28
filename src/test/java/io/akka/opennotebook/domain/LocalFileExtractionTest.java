package io.akka.opennotebook.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The original's "backward compatibility" {@code file_path} source-creation mode
 * (ApiSourceEndpoint's class doc): a caller-supplied path already inside the uploads directory,
 * with no multipart parsing involved at all. */
class LocalFileExtractionTest {

  private final Path uploadsRoot = Path.of(LocalFileExtraction.uploadsRoot()).toAbsolutePath().normalize();

  @AfterEach
  void cleanup() throws IOException {
    if (Files.exists(uploadsRoot)) {
      try (var stream = Files.walk(uploadsRoot)) {
        stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
      }
    }
  }

  private Path uploadedFile(String name, byte[] bytes) throws IOException {
    Files.createDirectories(uploadsRoot);
    Path file = uploadsRoot.resolve(name);
    Files.write(file, bytes);
    return file;
  }

  @Test
  void plainTextFileIsReadVerbatimAndTitledByItsOwnFilename() throws IOException {
    Path file = uploadedFile("note.txt", "The quick brown fox.".getBytes(StandardCharsets.UTF_8));
    var outcome = LocalFileExtraction.extract(file.toString());
    assertThat(outcome).isInstanceOf(Extraction.Outcome.Success.class);
    assertThat(((Extraction.Outcome.Success) outcome).content()).isEqualTo("The quick brown fox.");
    // Checked against the real source: content_core.extraction sets result.title =
    // os.path.basename(path) whenever the extractor itself produced none.
    assertThat(((Extraction.Outcome.Success) outcome).title()).isEqualTo("note.txt");
  }

  @Test
  void pdfTextLayerIsExtracted() throws IOException {
    Path file = uploadsRoot.resolve("doc.pdf");
    Files.createDirectories(uploadsRoot);
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(50, 700);
        stream.showText("Hello from a real PDF text layer.");
        stream.endText();
      }
      document.save(file.toFile());
    }

    var outcome = LocalFileExtraction.extract(file.toString());
    assertThat(outcome).isInstanceOf(Extraction.Outcome.Success.class);
    assertThat(((Extraction.Outcome.Success) outcome).content()).contains("Hello from a real PDF text layer.");
  }

  @Test
  void pathOutsideUploadsDirectoryIsRejected() throws IOException {
    Path outside = Files.createTempFile("open-notebook-lfi-", ".txt");
    Files.writeString(outside, "should never be read");
    try {
      var outcome = LocalFileExtraction.extract(outside.toString());
      assertThat(outcome).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
      assertThat(((Extraction.Outcome.PermanentFailure) outcome).message())
          .contains("must be within the uploads directory");
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void missingFileIsPermanentFailure() {
    var outcome = LocalFileExtraction.extract(uploadsRoot.resolve("does-not-exist.txt").toString());
    assertThat(outcome).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
  }

  @Test
  void docxIsRejectedWithThePoiGapNamedRatherThanMisreadAsText() throws IOException {
    // A real DOCX is a zip container; reading it as text would silently produce binary garbage.
    Path file = uploadedFile("report.docx", new byte[] {0x50, 0x4B, 0x03, 0x04});
    var outcome = LocalFileExtraction.extract(file.toString());
    assertThat(outcome).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
    assertThat(((Extraction.Outcome.PermanentFailure) outcome).message()).contains("Apache POI");
  }

  @Test
  void audioIsRejectedNamingTheTranscriptionGap() throws IOException {
    Path file = uploadedFile("clip.mp3", new byte[] {0, 1, 2, 3});
    var outcome = LocalFileExtraction.extract(file.toString());
    assertThat(outcome).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
    assertThat(((Extraction.Outcome.PermanentFailure) outcome).message()).contains("transcription");
  }

  @Test
  void blankPathIsPermanentFailure() {
    assertThat(LocalFileExtraction.extract(null)).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
    assertThat(LocalFileExtraction.extract("")).isInstanceOf(Extraction.Outcome.PermanentFailure.class);
  }
}
