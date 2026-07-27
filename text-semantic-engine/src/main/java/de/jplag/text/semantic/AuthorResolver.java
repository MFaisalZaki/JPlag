package de.jplag.text.semantic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a document's author from its document name, so that authors can differ per file within one batch (e.g. a
 * class-wide corpus where each file name starts with the student id).
 * <p>
 * A regex is applied to the file-name part of the document name (the segment after the last
 * {@link SubmissionReader#PATH_SEPARATOR}): the first capture group — or the whole match, if the regex has no groups —
 * is the author. Documents the regex does not match fall back to the fixed author, which is also used when no regex is
 * configured (one author per batch).
 */
public class AuthorResolver {

    private final String fixedAuthor;
    private final Pattern pattern;

    /**
     * Creates the resolver.
     * @param fixedAuthor the author for documents the pattern does not cover (empty if unknown).
     * @param authorPattern the regex extracting the author from a document's file name (null or blank for none).
     */
    public AuthorResolver(String fixedAuthor, String authorPattern) {
        this.fixedAuthor = fixedAuthor == null ? "" : fixedAuthor;
        this.pattern = authorPattern == null || authorPattern.isBlank() ? null : Pattern.compile(authorPattern);
    }

    /**
     * Resolves the author of the given document.
     * @param documentName the document name, possibly with encoded directories (e.g. {@code sub__dir__file}).
     * @return the author, or the empty string if unknown.
     */
    public String authorOf(String documentName) {
        if (pattern != null) {
            Matcher matcher = pattern.matcher(fileNamePart(documentName));
            if (matcher.find()) {
                String group = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                return group == null ? "" : group;
            }
        }
        return fixedAuthor;
    }

    private static String fileNamePart(String documentName) {
        int separator = documentName.lastIndexOf(SubmissionReader.PATH_SEPARATOR);
        return separator < 0 ? documentName : documentName.substring(separator + SubmissionReader.PATH_SEPARATOR.length());
    }
}
