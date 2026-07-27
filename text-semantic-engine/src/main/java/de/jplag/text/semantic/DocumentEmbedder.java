package de.jplag.text.semantic;

/**
 * Produces a single fixed-length embedding vector for a document's text, used to index documents for semantic
 * (vector-based) retrieval. Implementations may hold heavy resources (a neural model), hence {@link AutoCloseable}.
 */
public interface DocumentEmbedder extends AutoCloseable {

    /**
     * Embeds a document into a vector, returning a zero vector if the text has no embeddable content.
     * @param text the document text.
     * @return the embedding vector.
     */
    float[] embed(String text);

    @Override
    default void close() {
        // no resources by default
    }
}
