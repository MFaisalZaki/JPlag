package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests the ensemble's max-combination and union logic with stub backends, so no neural model is loaded.
 */
class EnsembleBackendTest {

    private static SubmissionPairSimilarity pair(String a, String b, double score, String... terms) {
        return new SubmissionPairSimilarity(a, b, score, java.util.Arrays.stream(terms).map(t -> new SharedTerm(t, 1.0)).toList());
    }

    @Test
    void testTakesTheHigherScoreAndKeepsLexicalTerms() {
        SimilarityBackend lexical = submissions -> List.of(pair("a", "b", 0.4, "shared"));
        SimilarityBackend semantic = submissions -> List.of(pair("a", "b", 0.7));
        List<SubmissionPairSimilarity> results = new EnsembleBackend(lexical, semantic).compare(List.of());

        assertEquals(1, results.size());
        assertEquals(0.7, results.get(0).similarity(), 1e-9, "Ensemble should take the higher (semantic) score");
        assertEquals(List.of("shared"), results.get(0).topSharedTerms().stream().map(SharedTerm::term).toList(),
                "Lexical shared-term explanation should be preserved");
    }

    @Test
    void testUnionOfPairsFlaggedByEitherBackend() {
        // "a,b" only lexical; "c,d" only semantic -> both must appear, sorted by combined score.
        SimilarityBackend lexical = submissions -> List.of(pair("a", "b", 0.6, "x"));
        SimilarityBackend semantic = submissions -> List.of(pair("c", "d", 0.9));
        List<SubmissionPairSimilarity> results = new EnsembleBackend(lexical, semantic).compare(List.of());

        assertEquals(2, results.size());
        assertEquals("c", results.get(0).firstSubmission(), "Highest combined score should rank first");
        assertEquals(0.9, results.get(0).similarity(), 1e-9);
        assertEquals(0.6, results.get(1).similarity(), 1e-9);
    }

    @Test
    void testMatchesRegardlessOfSubmissionOrder() {
        // The same pair reported as (a,b) by one backend and (b,a) by the other must be merged, not duplicated.
        SimilarityBackend lexical = submissions -> List.of(pair("a", "b", 0.3, "x"));
        SimilarityBackend semantic = submissions -> List.of(pair("b", "a", 0.8));
        List<SubmissionPairSimilarity> results = new EnsembleBackend(lexical, semantic).compare(List.of());

        assertEquals(1, results.size(), "The same unordered pair must be merged");
        assertEquals(0.8, results.get(0).similarity(), 1e-9);
    }
}
