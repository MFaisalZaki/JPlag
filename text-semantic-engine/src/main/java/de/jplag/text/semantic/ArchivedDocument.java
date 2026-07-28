package de.jplag.text.semantic;

import java.util.Set;

/**
 * An archived document retrieved from the corpus index, with its authors and stored text. The authors let the engine
 * tell self-plagiarism (reuse of the submitter's own prior work) from reuse of another author's work; a document has
 * more than one when it is a group or paired submission.
 * @param id the document id.
 * @param authors the document's authors (empty if unknown).
 * @param text the document's stored text.
 */
public record ArchivedDocument(String id, Set<String> authors, String text) {
}
