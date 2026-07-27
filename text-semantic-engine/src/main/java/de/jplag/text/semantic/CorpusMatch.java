package de.jplag.text.semantic;

/**
 * A single archived document retrieved as similar to a query document.
 * @param documentId the archived document's id (its submission name).
 * @param score the retrieval score (BM25 for lexical, cosine for semantic, or the fused rank score for the ensemble).
 */
public record CorpusMatch(String documentId, double score) {
}
