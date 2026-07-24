package de.jplag.text.semantic;

import java.util.List;

/**
 * A strategy for computing pairwise similarities between submissions. Implementations differ in what they compare:
 * {@link SemanticComparisonEngine} uses lexical TF-IDF cosine over normalized terms, while {@link SbertBackend} uses
 * neural sentence embeddings. Both return the same {@link SubmissionPairSimilarity} result type, so the rest of the
 * pipeline (reporting, CLI) is backend-agnostic.
 */
public interface SimilarityBackend {

    /**
     * Compares all submission pairs and returns those reaching the configured similarity threshold, most similar first.
     * @param submissions the analyzed submissions.
     * @return the reported pair similarities, sorted by descending similarity.
     */
    List<SubmissionPairSimilarity> compare(List<AnalyzedSubmission> submissions);
}
