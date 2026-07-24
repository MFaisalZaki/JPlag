package de.jplag.text.semantic;

/**
 * An archived document retrieved from the corpus index, with its stored text (needed to show and align matched passages
 * in an originality report).
 * @param id the document id.
 * @param text the document's stored text.
 */
public record ArchivedDocument(String id, String text) {
}
