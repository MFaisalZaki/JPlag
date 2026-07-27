package de.jplag.text.semantic;

/**
 * An archived document retrieved from the corpus index, with its author and stored text. The author lets the engine
 * tell self-plagiarism (reuse of the submitter's own prior work) from reuse of another author's work.
 * @param id the document id.
 * @param author the document's author (empty if unknown).
 * @param text the document's stored text.
 */
public record ArchivedDocument(String id, String author, String text) {
}
