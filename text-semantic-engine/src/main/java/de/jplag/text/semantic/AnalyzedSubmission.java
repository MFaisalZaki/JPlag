package de.jplag.text.semantic;

import java.util.Map;

/**
 * A single submission after tokenization and normalization: its name and the frequency of each normalized term. The
 * order of terms is intentionally discarded, which is what allows the engine to detect reordered and restructured
 * paraphrases.
 * @param name the submission name (its file or directory name).
 * @param termFrequencies the number of occurrences of each normalized term.
 */
public record AnalyzedSubmission(String name, Map<String, Integer> termFrequencies) {

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
