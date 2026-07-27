package de.jplag.text.semantic;

/**
 * Which signal retrieves candidate sources from the corpus index.
 */
public enum Backend {
    /** Lexical: BM25 over the normalized terms. */
    TFIDF,
    /** Semantic: nearest neighbour over the SBERT document embeddings. */
    SBERT,
    /** Both, fused by reciprocal-rank fusion. */
    ENSEMBLE
}
