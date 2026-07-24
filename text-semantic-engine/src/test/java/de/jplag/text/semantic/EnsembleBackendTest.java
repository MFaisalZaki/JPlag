package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests the ensemble's combination logic (maximum and weighted mean) and its unordered-pair union with stub backends,
 * so no neural model is loaded.
 */
class EnsembleBackendTest {

    private static SubmissionPairSimilarity pair(String a, String b, double score, String... terms) {
        return new SubmissionPairSimilarity(a, b, score, Arrays.stream(terms).map(t -> new SharedTerm(t, 1.0)).toList());
    }

    @Test
    void testMaxTakesTheHigherScoreAndKeepsLexicalTerms() {
        SimilarityBackend lexical = submissions -> List.of(pair("a", "b", 0.4, "shared"));
        SimilarityBackend semantic = submissions -> List.of(pair("a", "b", 0.7));
        List<SubmissionPairSimilarity> results = new EnsembleBackend(lexical, semantic, 0.0, null).compare(List.of());

        assertEquals(1, results.size());
        assertEquals(0.7, results.get(0).similarity(), 1e-9, "Max should take the higher (semantic) score");
        assertEquals(List.of("shared"), results.get(0).topSharedTerms().stream().map(SharedTerm::term).toList(),
                "Lexical shared-term explanation should be preserved");
    }

    @Test
    void testWeightedMeanBlendsBothScores() {
        SimilarityBackend lexical = submissions -> List.of(pair("a", "b", 0.4, "shared"));
        SimilarityBackend semantic = submissions -> List.of(pair("a", "b", 0.8));
        // weight 0.25 on TFIDF: 0.25*0.4 + 0.75*0.8 = 0.7
        List<SubmissionPairSimilarity> results = new EnsembleBackend(lexical, semantic, 0.0, 0.25).compare(List.of());

        assertEquals(1, results.size());
        assertEquals(0.7, results.get(0).similarity(), 1e-9, "Weighted mean should blend the two scores");
    }

    @Test
    void testWeightOneIsPureTfidfAndWeightZeroIsPureSbert() {
        SimilarityBackend lexical = submissions -> List.of(pair("a", "b", 0.4));
        SimilarityBackend semantic = submissions -> List.of(pair("a", "b", 0.8));
        assertEquals(0.4, new EnsembleBackend(lexical, semantic, 0.0, 1.0).compare(List.of()).get(0).similarity(), 1e-9);
        assertEquals(0.8, new EnsembleBackend(lexical, semantic, 0.0, 0.0).compare(List.of()).get(0).similarity(), 1e-9);
    }

    @Test
    void testWeightedMeanCanDropAPairBelowThreshold() {
        // A pair that only SBERT rates highly is pulled below threshold by a strong TFIDF weight.
        SimilarityBackend lexical = submissions -> List.of(pair("a", "b", 0.1));
        SimilarityBackend semantic = submissions -> List.of(pair("a", "b", 0.9));
        // weight 0.8 on TFIDF: 0.8*0.1 + 0.2*0.9 = 0.26 < threshold 0.5
        List<SubmissionPairSimilarity> results = new EnsembleBackend(lexical, semantic, 0.5, 0.8).compare(List.of());
        assertTrue(results.isEmpty(), "TFIDF weight should pull the semantically-inflated score below the threshold");
    }

    @Test
    void testUnionOfPairsAndUnorderedMerge() {
        SimilarityBackend lexical = submissions -> List.of(pair("a", "b", 0.6, "x"));
        SimilarityBackend semantic = submissions -> List.of(pair("c", "d", 0.9), pair("b", "a", 0.5));
        List<SubmissionPairSimilarity> results = new EnsembleBackend(lexical, semantic, 0.0, null).compare(List.of());

        assertEquals(2, results.size(), "(a,b) and (b,a) must merge; (c,d) is added");
        assertEquals("c", results.get(0).firstSubmission(), "Highest combined score ranks first");
        assertEquals(0.9, results.get(0).similarity(), 1e-9);
        assertEquals(0.6, results.get(1).similarity(), 1e-9, "max(0.6 lexical, 0.5 semantic) for the merged pair");
    }
}
