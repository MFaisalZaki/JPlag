package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SparseVectorTest {

    private static final double DELTA = 1e-9;

    @Test
    void testIdenticalVectorsHaveCosineOne() {
        SparseVector vector = new SparseVector(Map.of("a", 1.0, "b", 2.0));
        assertEquals(1.0, vector.cosineSimilarity(vector), DELTA);
    }

    @Test
    void testDisjointVectorsHaveCosineZero() {
        SparseVector first = new SparseVector(Map.of("a", 1.0));
        SparseVector second = new SparseVector(Map.of("b", 1.0));
        assertEquals(0.0, first.cosineSimilarity(second), DELTA);
    }

    @Test
    void testDotProduct() {
        SparseVector first = new SparseVector(Map.of("a", 1.0, "b", 2.0));
        SparseVector second = new SparseVector(Map.of("a", 3.0, "b", 1.0, "c", 5.0));
        assertEquals(1.0 * 3.0 + 2.0 * 1.0, first.dotProduct(second), DELTA);
    }

    @Test
    void testNormalizedVectorIsUnitLength() {
        SparseVector normalized = new SparseVector(Map.of("a", 3.0, "b", 4.0)).normalized();
        assertEquals(1.0, normalized.norm(), DELTA);
        assertEquals(0.6, normalized.weight("a"), DELTA);
        assertEquals(0.8, normalized.weight("b"), DELTA);
    }

    @Test
    void testZeroVectorIsHandledGracefully() {
        SparseVector zero = new SparseVector(Map.of());
        SparseVector other = new SparseVector(Map.of("a", 1.0));
        assertEquals(0.0, zero.norm(), DELTA);
        assertEquals(0.0, zero.cosineSimilarity(other), DELTA);
        assertTrue(zero.normalized().terms().isEmpty());
    }
}
