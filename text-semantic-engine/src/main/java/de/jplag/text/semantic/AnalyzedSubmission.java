package de.jplag.text.semantic;

import java.util.Map;

/**
 * A single submission after reading: its name, the frequency of each normalized term, and its raw text. The normalized
 * term bag (order discarded) feeds the lexical TF-IDF backend; the raw text feeds the semantic SBERT backend.
 * @param name the submission name (its file or directory name).
 * @param termFrequencies the number of occurrences of each normalized term.
 * @param text the raw (un-normalized) text content of the submission.
 */
public record AnalyzedSubmission(String name, Map<String, Integer> termFrequencies, String text) {

    /**
     * Creates a submission without raw text (used where only the term bag is needed, e.g. in tests).
     * @param name the submission name.
     * @param termFrequencies the normalized term frequencies.
     */
    public AnalyzedSubmission(String name, Map<String, Integer> termFrequencies) {
        this(name, termFrequencies, "");
    }

    /**
     * @return the total number of (non-distinct) terms in the submission.
     */
    public int length() {
        return termFrequencies.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * @return whether the submission contains no terms (e.g. an empty or unreadable file).
     */
    public boolean isEmpty() {
        return termFrequencies.isEmpty();
    }
}
