package io.akka.opennotebook.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into overlapping chunks for embedding (SPEC-001 §Search) — the source's
 * {@code open_notebook/utils/chunking.py} chunks by token count ({@code OPEN_NOTEBOOK_CHUNK_SIZE},
 * default 400, 15% overlap); this port approximates a token with a whitespace-delimited word,
 * which is not the same tokenizer but the same shape (fixed window, fixed overlap fraction) and
 * is exact where it matters for this port's rules: a short text is one chunk, a long text is
 * several, and adjacent chunks share content at the boundary.
 */
public final class Chunking {

  private Chunking() {}

  public static final int DEFAULT_CHUNK_SIZE_WORDS = 400;
  public static final double OVERLAP_RATIO = 0.15;

  public static List<String> chunk(String text) {
    return chunk(text, DEFAULT_CHUNK_SIZE_WORDS);
  }

  public static List<String> chunk(String text, int chunkSizeWords) {
    if (text == null || text.isBlank()) return List.of();
    String[] words = text.trim().split("\\s+");
    if (words.length <= chunkSizeWords) {
      return List.of(text.trim());
    }
    int overlap = (int) Math.round(chunkSizeWords * OVERLAP_RATIO);
    int step = Math.max(1, chunkSizeWords - overlap);
    List<String> chunks = new ArrayList<>();
    for (int start = 0; start < words.length; start += step) {
      int end = Math.min(words.length, start + chunkSizeWords);
      chunks.add(String.join(" ", java.util.Arrays.asList(words).subList(start, end)));
      if (end == words.length) break;
    }
    return chunks;
  }

  public static double cosineSimilarity(List<Double> a, List<Double> b) {
    if (a.size() != b.size() || a.isEmpty()) return 0.0;
    double dot = 0, normA = 0, normB = 0;
    for (int i = 0; i < a.size(); i++) {
      dot += a.get(i) * b.get(i);
      normA += a.get(i) * a.get(i);
      normB += b.get(i) * b.get(i);
    }
    if (normA == 0 || normB == 0) return 0.0;
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }
}
