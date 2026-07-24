package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TfIdfVectorizerTest {

    private static final double DELTA = 1e-9;

    @Test
    void testVectorsAreUnitLength() {
        List<AnalyzedSubmission> submissions = List.of(new AnalyzedSubmission("a", Map.of("apple", 3, "banana", 1)),
                new AnalyzedSubmission("b", Map.of("banana", 2, "cherry", 5)));
        TfIdfVectorizer vectorizer = new TfIdfVectorizer(submissions);
        for (AnalyzedSubmission submission : submissions) {
            assertEquals(1.0, vectorizer.vectorize(submission).norm(), DELTA);
        }
    }

    @Test
    void testRareTermOutweighsCommonTerm() {
        // "common" appears in both submissions, "unique" only in the first, so "unique" must receive a higher weight.
        List<AnalyzedSubmission> submissions = List.of(new AnalyzedSubmission("a", Map.of("common", 1, "unique", 1)),
                new AnalyzedSubmission("b", Map.of("common", 1, "other", 1)));
        SparseVector vector = new TfIdfVectorizer(submissions).vectorize(submissions.get(0));
        assertTrue(vector.weight("unique") > vector.weight("common"), "A term appearing in fewer submissions should have a higher TF-IDF weight");
    }
}
