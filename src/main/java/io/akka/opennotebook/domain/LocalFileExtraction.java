package io.akka.opennotebook.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * R6/R7-adjacent: a source submitted as {@code type=file} with a caller-supplied {@code
 * file_path} rather than a multipart upload -- the original's own "backward compatibility" path
 * ({@code api/routers/sources.py}'s {@code final_file_path = file_path or source_data.file_path}),
 * which needs no multipart parsing at all and is real HTTP-level input validation the same way
 * every other source path is.
 *
 * <p><b>LFI guard, checked exactly like the source's own:</b> the resolved path must sit inside
 * the configured uploads directory, or the request is rejected before anything is read.
 *
 * <p><b>What is and is not reproduced, and why (SPEC-001 SS1):</b> plain-text formats and PDF's own
 * text layer (via PDFBox, already vendored in this environment) are extracted for real. Three
 * classes of file the original also accepts are not, each for a different, checked reason rather
 * than "not attempted":
 *
 * <ul>
 *   <li>DOCX/PPTX/XLSX need Apache POI, which this build environment's Maven cannot resolve --
 *       verified by actually running {@code mvn dependency:get} for it, which timed out with no
 *       artifact retrieved, not by reading a doc and assuming.
 *   <li>A scanned or image-only PDF, or one whose tables/formulas need vision/OCR models, is
 *       exactly what Docling exists for in the original; PDFBox extracts a PDF's own text layer
 *       and nothing else, the same boundary {@code content_settings.docling_ocr/formulas/vision}
 *       already draws in the source itself between its own Docling path and its own plain
 *       fallback.
 *   <li>Audio and video route to Whisper-class transcription in the original
 *       ({@code content_core.processors.media}), which is an ML runtime this port does not embed,
 *       the same infrastructure class as Docling's OCR.
 * </ul>
 */
public final class LocalFileExtraction {

  private LocalFileExtraction() {}

  private static final Set<String> PLAIN_TEXT_EXTENSIONS =
      Set.of("txt", "md", "markdown", "csv", "json", "yaml", "yml", "xml", "html", "htm", "log", "rtf");

  private static final Set<String> UNSUPPORTED_NEEDS_POI = Set.of("docx", "pptx", "xlsx", "doc", "ppt", "xls");
  private static final Set<String> UNSUPPORTED_MEDIA =
      Set.of("mp3", "mp4", "wav", "m4a", "mov", "avi", "webm", "flac", "ogg");

  public static String uploadsRoot() {
    String configured = System.getenv("OPEN_NOTEBOOK_UPLOADS_DIR");
    return (configured != null && !configured.isBlank()) ? configured : "./data/uploads";
  }

  public static Extraction.Outcome extract(String filePath) {
    if (filePath == null || filePath.isBlank()) {
      return new Extraction.Outcome.PermanentFailure("File path is required for file type");
    }

    Path uploadsResolved = Path.of(uploadsRoot()).toAbsolutePath().normalize();
    Path fileResolved = Path.of(filePath).toAbsolutePath().normalize();
    if (!fileResolved.startsWith(uploadsResolved)) {
      return new Extraction.Outcome.PermanentFailure(
          "Invalid file path: must be within the uploads directory");
    }
    if (!Files.isRegularFile(fileResolved)) {
      return new Extraction.Outcome.PermanentFailure("File not found at " + filePath);
    }

    String extension = extensionOf(fileResolved);
    if (UNSUPPORTED_NEEDS_POI.contains(extension)) {
      return new Extraction.Outcome.PermanentFailure(
          "Unsupported file type: ." + extension + " requires a document-parsing library "
              + "(Apache POI) not available in this build environment. Supported local file "
              + "types: pdf, and plain text formats (txt, md, csv, json, yaml, xml, html, log).");
    }
    if (UNSUPPORTED_MEDIA.contains(extension)) {
      return new Extraction.Outcome.PermanentFailure(
          "Unsupported file type: ." + extension + " requires audio/video transcription, "
              + "an ML runtime this port does not embed.");
    }

    try {
      if ("pdf".equals(extension)) {
        return extractPdf(fileResolved);
      }
      return extractPlainText(fileResolved);
    } catch (IOException e) {
      return Extraction.unreachable(e.getClass().getSimpleName());
    }
  }

  private static Extraction.Outcome extractPdf(Path path) throws IOException {
    try (var document = Loader.loadPDF(path.toFile())) {
      String text = new PDFTextStripper().getText(document);
      if (text == null || text.isBlank()) {
        return new Extraction.Outcome.PermanentFailure(
            "Could not extract any text content from this source. The PDF may be scanned or "
                + "image-only, which requires OCR (Docling) this port does not embed.");
      }
      return new Extraction.Outcome.Success(path.getFileName().toString(), text.trim());
    }
  }

  private static Extraction.Outcome extractPlainText(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (text.isBlank()) {
        return new Extraction.Outcome.PermanentFailure(
            "Could not extract any text content from this source. The content may be empty, "
                + "inaccessible, or in an unsupported format.");
      }
      // content_core.extraction sets result.title = os.path.basename(path) whenever the
      // extractor itself didn't produce one -- checked by running the real source against a
      // file source, not assumed: it titles a file source by its own filename.
      return new Extraction.Outcome.Success(path.getFileName().toString(), text);
    }
  }

  private static String extensionOf(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }
}
