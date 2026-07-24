package de.jplag.text.semantic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns analyzed submissions into unit-normalized TF-IDF vectors.
 * <p>
 * Term frequency is dampened sub-linearly ({@code 1 + ln(count)}) so that a term repeated many times in one submission
 * does not dominate. Inverse document frequency uses the smoothed form {@code ln((N + 1) / (df + 1)) + 1}, which keeps
 * the weight of ubiquitous terms small but never zero (so a shared rare vocabulary still contributes even if it appears
 * in every submission). Vectors are L2-normalized, making the cosine similarity of two submissions equal to the dot
 * product of their vectors.
 */
public class TfIdfVectorizer {

    private final Map<String, Double> inverseDocumentFrequency;

    /**
     * Builds the vectorizer, computing inverse document frequencies over the whole corpus.
     * @param submissions all analyzed submissions.
     */
    public TfIdfVectorizer(List<AnalyzedSubmission> submissions) {
        this.inverseDocumentFrequency = computeInverseDocumentFrequency(submissions);
    }

    private static Map<String, Double> computeInverseDocumentFrequency(List<AnalyzedSubmission> submissions) {
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (AnalyzedSubmission submission : submissions) {
            for (String term : submission.termFrequencies().keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        int documentCount = submissions.size();
        Map<String, Double> idf = new HashMap<>();
        documentFrequency.forEach((term, df) -> idf.put(term, Math.log((documentCount + 1.0) / (df + 1.0)) + 1.0));
        return idf;
    }

    /**
     * Vectorizes a single submission into a unit-normalized TF-IDF vector.
     * @param submission the submission to vectorize.
     * @return the normalized vector.
     */
    public SparseVector vectorize(AnalyzedSubmission submission) {
        Map<String, Double> weights = new HashMap<>();
        submission.termFrequencies().forEach((term, count) -> {
            double termFrequency = 1.0 + Math.log(count);
            double idf = inverseDocumentFrequency.getOrDefault(term, 0.0);
            weights.put(term, termFrequency * idf);
        });
        return new SparseVector(weights).normalized();
    }
}
