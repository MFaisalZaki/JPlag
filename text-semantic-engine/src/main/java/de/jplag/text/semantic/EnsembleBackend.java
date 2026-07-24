package de.jplag.text.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A similarity backend that combines the lexical TF-IDF backend and the semantic SBERT backend. The two per-pair scores
 * are combined either by <em>maximum</em> (flag a pair if either signal is strong) or, when an ensemble weight is set,
 * by a <em>weighted mean</em> {@code w * tfidf + (1 - w) * sbert} (a calibrated blend where TF-IDF can pull a
 * semantically-inflated score back down).
 * <p>
 * The two delegates are run unfiltered (threshold 0) so the ensemble has every pair's score from both, then the
 * combined score is filtered against the real threshold. The TF-IDF shared-term explanation is kept, which the SBERT
 * backend cannot provide.
 */
public class EnsembleBackend implements SimilarityBackend {

    private final SimilarityBackend lexicalBackend;
    private final SimilarityBackend semanticBackend;
    private final double similarityThreshold;
    private final Double tfidfWeight;

    /**
     * Creates the ensemble over the standard TF-IDF and SBERT backends, run unfiltered so all pair scores are available.
     * @param configuration the engine configuration (supplies the threshold and optional ensemble weight).
     */
    public EnsembleBackend(SemanticEngineConfiguration configuration) {
        this(new SemanticComparisonEngine(configuration.withSimilarityThreshold(0.0)), new SbertBackend(configuration.withSimilarityThreshold(0.0)),
                configuration.similarityThreshold(), configuration.ensembleWeight());
    }

    /**
     * Creates the ensemble over two explicit backends (for testing).
     * @param lexicalBackend the lexical backend, whose shared-term explanations are kept.
     * @param semanticBackend the semantic backend.
     * @param similarityThreshold the minimum combined score to report.
     * @param tfidfWeight the weight on TF-IDF in {@code [0, 1]}, or {@code null} to combine by maximum.
     */
    EnsembleBackend(SimilarityBackend lexicalBackend, SimilarityBackend semanticBackend, double similarityThreshold, Double tfidfWeight) {
        this.lexicalBackend = lexicalBackend;
        this.semanticBackend = semanticBackend;
        this.similarityThreshold = similarityThreshold;
        this.tfidfWeight = tfidfWeight;
    }

    @Override
    public List<SubmissionPairSimilarity> compare(List<AnalyzedSubmission> submissions) {
        Map<List<String>, SubmissionPairSimilarity> lexical = index(lexicalBackend.compare(submissions));
        Map<List<String>, SubmissionPairSimilarity> semantic = index(semanticBackend.compare(submissions));

        Set<List<String>> pairs = new LinkedHashSet<>();
        pairs.addAll(lexical.keySet());
        pairs.addAll(semantic.keySet());

        List<SubmissionPairSimilarity> results = new ArrayList<>();
        for (List<String> pair : pairs) {
            SubmissionPairSimilarity lexicalResult = lexical.get(pair);
            SubmissionPairSimilarity semanticResult = semantic.get(pair);
            double combined = combine(similarityOf(lexicalResult), similarityOf(semanticResult));
            if (combined >= similarityThreshold) {
                SubmissionPairSimilarity present = lexicalResult != null ? lexicalResult : semanticResult;
                List<SharedTerm> terms = lexicalResult != null ? lexicalResult.topSharedTerms() : List.of();
                results.add(new SubmissionPairSimilarity(present.firstSubmission(), present.secondSubmission(), combined, terms));
            }
        }
        results.sort(Comparator.comparingDouble(SubmissionPairSimilarity::similarity).reversed());
        return results;
    }

    private double combine(double tfidf, double sbert) {
        if (tfidfWeight == null) {
            return Math.max(tfidf, sbert);
        }
        return tfidfWeight * tfidf + (1.0 - tfidfWeight) * sbert;
    }

    private static double similarityOf(SubmissionPairSimilarity result) {
        return result == null ? 0.0 : result.similarity();
    }

    private static Map<List<String>, SubmissionPairSimilarity> index(List<SubmissionPairSimilarity> results) {
        Map<List<String>, SubmissionPairSimilarity> map = new HashMap<>();
        for (SubmissionPairSimilarity result : results) {
            map.put(unorderedKey(result.firstSubmission(), result.secondSubmission()), result);
        }
        return map;
    }

    private static List<String> unorderedKey(String first, String second) {
        return first.compareTo(second) <= 0 ? List.of(first, second) : List.of(second, first);
    }
}
