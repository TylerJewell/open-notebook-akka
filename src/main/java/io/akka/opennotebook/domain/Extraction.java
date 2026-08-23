package io.akka.opennotebook.domain;

import java.util.regex.Pattern;

/**
 * R5–R7: what submitted content turns into, and the one rule for which failures are permanent.
 *
 * <p>D-1: this port's extraction step has no failure mode that is transient by nature —
 * submitted text cannot fail, and a URL fetch either returns a page or it does not. So {@link
 * Outcome} has no "retry me" case; a workflow step timeout, not a returned value, is what would
 * cover a fetch that is merely slow.
 */
public final class Extraction {

  private Extraction() {}

  public sealed interface Outcome {
    record Success(String title, String content) implements Outcome {}

    record PermanentFailure(String message) implements Outcome {}
  }

  /** R6: plain submitted text becomes fullText verbatim, with no title of its own. */
  public static Outcome plainText(String content) {
    if (content == null || content.isBlank()) {
      return new Outcome.PermanentFailure(
          "Could not extract any text content from this source. The content may be empty, "
              + "inaccessible, or in an unsupported format.");
    }
    return new Outcome.Success(null, content);
  }

  /** R7: a fetched page is reduced to its own title and its visible text, tags stripped. */
  public static Outcome fromFetchedHtml(String html) {
    if (html == null || html.isBlank()) {
      return new Outcome.PermanentFailure(
          "Could not extract any text content from this source. The content may be empty, "
              + "inaccessible, or in an unsupported format.");
    }
    String title = extractTitle(html);
    String text = stripToVisibleText(html);
    if (text.isBlank()) {
      return new Outcome.PermanentFailure(
          "Could not extract any text content from this source. The content may be empty, "
              + "inaccessible, or in an unsupported format.");
    }
    return new Outcome.Success(title, text);
  }

  public static Outcome unreachable(String reason) {
    return new Outcome.PermanentFailure(
        "Could not extract content from this source. The URL or file may be unreachable, "
            + "invalid, or in an unsupported format. (%s)".formatted(reason));
  }

  private static final Pattern TITLE_TAG =
      Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern SCRIPT_OR_STYLE =
      Pattern.compile("<(script|style)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private static String extractTitle(String html) {
    var matcher = TITLE_TAG.matcher(html);
    if (matcher.find()) {
      return collapseWhitespace(matcher.group(1));
    }
    return null;
  }

  private static String stripToVisibleText(String html) {
    String withoutScripts = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
    String withoutTags = ANY_TAG.matcher(withoutScripts).replaceAll(" ");
    return collapseWhitespace(withoutTags);
  }

  private static String collapseWhitespace(String s) {
    return WHITESPACE.matcher(s.trim()).replaceAll(" ").trim();
  }
}
