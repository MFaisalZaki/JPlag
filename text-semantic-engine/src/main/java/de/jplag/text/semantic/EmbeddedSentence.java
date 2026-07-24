package de.jplag.text.semantic;

/**
 * A sentence together with its embedding vector, used for sentence-level attribution in originality reports.
 * @param text the sentence text.
 * @param vector the (unit-length) sentence embedding.
 */
public record EmbeddedSentence(String text, float[] vector) {
}
