package de.jplag.text.semantic;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a document's authors, so that authors can differ per file within one batch (e.g. a class-wide corpus where
 * each file name starts with the student id) and a document can have more than one.
 * <p>
 * A file-name regex is applied to the file-name part of the document name (the segment after the last
 * {@link SubmissionReader#PATH_SEPARATOR}): the first capture group — or the whole match, if the regex has no groups —
 * is the author. Documents the regex does not match fall back to the fixed author, which is also used when no regex is
 * configured (one author per batch).
 * <p>
 * A second regex can be run over the start of the document's own text to pick up co-authors. Group and paired
 * courseworks are submitted once per member, so each member's copy is the partner's work as well and would otherwise be
 * reported as a perfect match against them; the file name only carries the submitter's id, but the cover sheet lists
 * everyone's, which is what this reads.
 */
public class AuthorResolver {

    /** Leading characters of a document searched for co-authors: the cover sheet, not the whole text. */
    private static final int COVER_SHEET_CHARACTERS = 2000;

    private final String fixedAuthor;
    private final Pattern namePattern;
    private final Pattern coauthorPattern;

    /**
     * Creates the resolver.
     * @param fixedAuthor the author for documents the pattern does not cover (empty if unknown).
     * @param authorPattern the regex extracting the author from a document's file name (null or blank for none).
     * @param coauthorPattern the regex whose every match in the document's cover sheet is a co-author (null or blank for
     * none).
     */
    public AuthorResolver(String fixedAuthor, String authorPattern, String coauthorPattern) {
        this.fixedAuthor = fixedAuthor == null ? "" : fixedAuthor;
        this.namePattern = compile(authorPattern);
        this.coauthorPattern = compile(coauthorPattern);
    }

    private static Pattern compile(String regex) {
        return regex == null || regex.isBlank() ? null : Pattern.compile(regex);
    }

    /**
     * Resolves the authors of the given document.
     * @param documentName the document name, possibly with encoded directories (e.g. {@code sub__dir__file}).
     * @param text the document's text, whose cover sheet is searched for co-authors (may be empty).
     * @return the authors; empty if none are known.
     */
    public Set<String> authorsOf(String documentName, String text) {
        Set<String> authors = new LinkedHashSet<>();
        add(authors, fromFileName(documentName));
        if (coauthorPattern != null && text != null && !text.isEmpty()) {
            Matcher matcher = coauthorPattern.matcher(text.substring(0, Math.min(text.length(), COVER_SHEET_CHARACTERS)));
            while (matcher.find()) {
                add(authors, matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group());
            }
        }
        return authors;
    }

    private String fromFileName(String documentName) {
        if (namePattern != null) {
            Matcher matcher = namePattern.matcher(fileNamePart(documentName));
            if (matcher.find()) {
                return matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
            }
        }
        return fixedAuthor;
    }

    private static void add(Set<String> authors, String author) {
        if (author != null && !author.isBlank()) {
            authors.add(author);
        }
    }

    private static String fileNamePart(String documentName) {
        int separator = documentName.lastIndexOf(SubmissionReader.PATH_SEPARATOR);
        return separator < 0 ? documentName : documentName.substring(separator + SubmissionReader.PATH_SEPARATOR.length());
    }
}
