package de.jplag.text.semantic;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * An immutable sparse vector mapping terms to weights, used to represent a submission in the TF-IDF vector space. Only
 * non-zero components are stored, which suits the very high-dimensional, sparse term space of natural-language text.
 */
public final class SparseVector {

    private final Map<String, Double> weights;
    private final double norm;

    /**
     * Creates a sparse vector. The given map is copied defensively.
     * @param weights the term weights.
     */
    public SparseVector(Map<String, Double> weights) {
        this.weights = new HashMap<>(weights);
        this.norm = Math.sqrt(this.weights.values().stream().mapToDouble(value -> value * value).sum());
    }

    /**
     * @param term a term.
     * @return the weight of the term, or {@code 0.0} if absent.
     */
    public double weight(String term) {
        return weights.getOrDefault(term, 0.0);
    }

    /**
     * @return the (unmodifiable) set of terms with non-zero weight.
     */
    public Set<String> terms() {
        return weights.keySet();
    }

    /**
     * @return the Euclidean (L2) norm of the vector.
     */
    public double norm() {
        return norm;
    }

    /**
     * Returns a unit-length copy of this vector, so that dot products between normalized vectors equal their cosine
     * similarity. A zero vector is returned unchanged.
     * @return the normalized vector.
     */
    public SparseVector normalized() {
        if (norm == 0.0) {
            return this;
        }
        Map<String, Double> normalized = new HashMap<>();
        weights.forEach((term, weight) -> normalized.put(term, weight / norm));
        return new SparseVector(normalized);
    }

    /**
     * Computes the dot product with another vector, iterating over the smaller vector for efficiency.
     * @param other the other vector.
     * @return the dot product.
     */
    public double dotProduct(SparseVector other) {
        SparseVector smaller = this.weights.size() <= other.weights.size() ? this : other;
        SparseVector larger = smaller == this ? other : this;
        double sum = 0.0;
        for (Map.Entry<String, Double> entry : smaller.weights.entrySet()) {
            sum += entry.getValue() * larger.weight(entry.getKey());
        }
        return sum;
    }

    /**
     * Computes the cosine similarity with another vector, in the range {@code [0, 1]} for non-negative weights. Returns
     * {@code 0.0} if either vector is a zero vector.
     * @param other the other vector.
     * @return the cosine similarity.
     */
    public double cosineSimilarity(SparseVector other) {
        if (this.norm == 0.0 || other.norm == 0.0) {
            return 0.0;
        }
        return dotProduct(other) / (this.norm * other.norm);
    }
}
