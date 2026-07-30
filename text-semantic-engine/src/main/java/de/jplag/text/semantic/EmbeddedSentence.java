package de.jplag.text.semantic;

/**
 * A sentence together with its embedding vector and its position in the document it was taken from, used for
 * sentence-level attribution in originality reports.
 * <p>
 * The offsets are what lets a report render the <em>document's own text</em> — paragraphs, line breaks, headings and
 * all — and wrap the matched spans where they sit, rather than re-joining the sentences the splitter happened to
 * produce. Re-joining loses every blank line and every short line that was never a sentence.
 * @param text the sentence text, i.e. the document text between the two offsets.
 * @param vector the (unit-length) sentence embedding.
 * @param begin the index of the sentence's first character in the document text.
 * @param end the index one past the sentence's last character in the document text.
 */
public record EmbeddedSentence(String text, float[] vector, int begin, int end) {
}
