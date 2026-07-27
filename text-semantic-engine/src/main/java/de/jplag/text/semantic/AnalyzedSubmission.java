package de.jplag.text.semantic;

import java.util.Map;

/**
 * A single document after reading: its name, the frequency of each normalized term, and its raw text. The normalized
 * term bag (order discarded) feeds the index's lexical BM25 field; the raw text feeds the SBERT embeddings.
 * @param name the document name (its path relative to the ingested root, extension dropped).
 * @param termFrequencies the number of occurrences of each normalized term.
 * @param text the raw (un-normalized) text content.
 */
public record AnalyzedSubmission(String name, Map<String, Integer> termFrequencies, String text) {

    /**
     * @return whether the document contains no terms (e.g. an empty or unreadable file).
     */
    public boolean isEmpty() {
        return termFrequencies.isEmpty();
    }
}
