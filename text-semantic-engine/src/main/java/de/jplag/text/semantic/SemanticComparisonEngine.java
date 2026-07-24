package de.jplag.text.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standalone semantic comparison engine. It vectorizes every submission with {@link TfIdfVectorizer} and compares
 * all pairs by cosine similarity. Because the vectors are bags of normalized terms, similarity is independent of word
 * order, so paraphrases that reorder or restructure content are still detected.
 */
public class SemanticComparisonEngine implements SimilarityBackend {

    private static final Logger logger = LoggerFactory.getLogger(SemanticComparisonEngine.class);

    private final SemanticEngineConfiguration configuration;

    /**
     * Creates the engine.
     * @param configuration the engine configuration.
     */
    public SemanticComparisonEngine(SemanticEngineConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public List<SubmissionPairSimilarity> compare(List<AnalyzedSubmission> submissions) {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer(submissions);
        List<SparseVector> vectors = submissions.stream().map(vectorizer::vectorize).toList();
        logger.debug("Comparing {} submissions ({} pairs).", submissions.size(), submissions.size() * (submissions.size() - 1) / 2);

        List<SubmissionPairSimilarity> results = new ArrayList<>();
        for (int i = 0; i < submissions.size(); i++) {
            for (int j = i + 1; j < submissions.size(); j++) {
                double similarity = vectors.get(i).cosineSimilarity(vectors.get(j));
                if (similarity >= configuration.similarityThreshold()) {
                    List<SharedTerm> topTerms = topSharedTerms(vectors.get(i), vectors.get(j));
                    results.add(new SubmissionPairSimilarity(submissions.get(i).name(), submissions.get(j).name(), similarity, topTerms));
                }
            }
        }
        results.sort(Comparator.comparingDouble(SubmissionPairSimilarity::similarity).reversed());
        return results;
    }

    /**
     * Determines which shared terms contributed most to a pair's similarity. The contribution of a term is the product of
     * its (normalized) weight in both submissions, i.e. its additive share of the cosine dot product.
     */
    private List<SharedTerm> topSharedTerms(SparseVector first, SparseVector second) {
        SparseVector smaller = first.terms().size() <= second.terms().size() ? first : second;
        SparseVector larger = smaller == first ? second : first;
        return smaller.terms().stream().map(term -> new SharedTerm(term, smaller.weight(term) * larger.weight(term)))
                .filter(shared -> shared.contribution() > 0.0).sorted(Comparator.comparingDouble(SharedTerm::contribution).reversed())
                .limit(configuration.topSharedTermCount()).toList();
    }
}
