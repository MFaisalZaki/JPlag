package de.jplag.text.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A similarity backend that combines the lexical TF-IDF backend and the semantic SBERT backend, reporting the
 * <em>higher</em> of the two scores for each pair. A pair is therefore flagged if <em>either</em> signal is strong: the
 * lexical backend catches reuse that keeps vocabulary, the semantic backend catches rewrites that keep meaning.
 * <p>
 * Combining by maximum relies on both delegates filtering at the same threshold as the ensemble (which is how
 * {@link SemanticEngineConfiguration#createBackend()} wires them): the union of their reported pairs is then exactly
 * the set whose maximum reaches the threshold. Because SBERT scores tend to run higher than TF-IDF scores, the maximum
 * is often the SBERT score; TF-IDF dominates only for strongly lexical pairs.
 */
public class EnsembleBackend implements SimilarityBackend {

    private final SimilarityBackend lexicalBackend;
    private final SimilarityBackend semanticBackend;

    /**
     * Creates the ensemble over the standard TF-IDF and SBERT backends.
     * @param configuration the engine configuration, passed to both delegates.
     */
    public EnsembleBackend(SemanticEngineConfiguration configuration) {
        this(new SemanticComparisonEngine(configuration), new SbertBackend(configuration));
    }

    /**
     * Creates the ensemble over two explicit backends (for testing).
     * @param lexicalBackend the lexical backend, whose shared-term explanations are kept.
     * @param semanticBackend the semantic backend.
     */
    EnsembleBackend(SimilarityBackend lexicalBackend, SimilarityBackend semanticBackend) {
        this.lexicalBackend = lexicalBackend;
        this.semanticBackend = semanticBackend;
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
            double combined = Math.max(similarityOf(lexicalResult), similarityOf(semanticResult));
            SubmissionPairSimilarity present = lexicalResult != null ? lexicalResult : semanticResult;
            // Keep the lexical shared-term explanation when available; SBERT provides none.
            List<SharedTerm> terms = lexicalResult != null ? lexicalResult.topSharedTerms() : List.of();
            results.add(new SubmissionPairSimilarity(present.firstSubmission(), present.secondSubmission(), combined, terms));
        }
        results.sort(Comparator.comparingDouble(SubmissionPairSimilarity::similarity).reversed());
        return results;
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
