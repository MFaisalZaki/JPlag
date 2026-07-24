package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests the SBERT backend's alignment math directly, without loading the neural model (which would require downloading
 * weights). The model-dependent path is exercised manually via {@code --backend sbert}.
 */
class SbertBackendTest {

    private static final double DELTA = 1e-6;

    @Test
    void testAlignmentOfIdenticalDocumentsIsOne() {
        List<float[]> document = List.of(SbertBackend.normalize(new float[] {1, 0, 0}), SbertBackend.normalize(new float[] {0, 1, 0}));
        assertEquals(1.0, SbertBackend.alignmentScore(document, document), DELTA);
    }

    @Test
    void testAlignmentOfOrthogonalDocumentsIsZero() {
        List<float[]> first = List.of(SbertBackend.normalize(new float[] {1, 0, 0}));
        List<float[]> second = List.of(SbertBackend.normalize(new float[] {0, 1, 0}));
        assertEquals(0.0, SbertBackend.alignmentScore(first, second), DELTA);
    }

    @Test
    void testAlignmentRewardsBestMatchNotAverage() {
        // The source sentence aligns perfectly with one target sentence; the unrelated second target must not dilute it.
        List<float[]> first = List.of(SbertBackend.normalize(new float[] {1, 0, 0}));
        List<float[]> second = List.of(SbertBackend.normalize(new float[] {1, 0, 0}), SbertBackend.normalize(new float[] {0, 1, 0}));
        // first->second best match is 1.0; second->first averages best matches (1.0 and 0.0) = 0.5; symmetric mean = 0.75.
        assertEquals(0.75, SbertBackend.alignmentScore(first, second), DELTA);
    }

    @Test
    void testEmptyDocumentYieldsZero() {
        assertEquals(0.0, SbertBackend.alignmentScore(List.of(), List.of(new float[] {1, 0, 0})), DELTA);
    }

    @Test
    void testNormalizeProducesUnitVector() {
        float[] normalized = SbertBackend.normalize(new float[] {3, 4});
        assertEquals(1.0, Math.sqrt(SbertBackend.cosine(normalized, normalized)), DELTA);
        assertTrue(Math.abs(normalized[0] - 0.6f) < 1e-4);
    }
}
