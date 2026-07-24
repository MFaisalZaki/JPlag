package de.jplag.text.semantic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristically determines whether a sentence in a query document attributes its content, by looking for quotation
 * marks or a citation (author-year, numeric reference, URL, or DOI) within the sentence.
 * <p>
 * This is a lightweight, language- and style-agnostic check on the extracted text; it recognises the common citation
 * shapes that survive PDF-to-text extraction. It cannot see footnote superscripts (usually lost in extraction) and does
 * not validate that a citation actually corresponds to the matched source.
 */
public final class CitationDetector {

    /** A parenthetical containing a 4-digit year, e.g. "(Smith, 2020)", "(Smith et al., 2019)", or "(2020)". */
    private static final Pattern PARENTHETICAL_CITATION = Pattern.compile("\\([^)]*\\b(?:19|20)\\d{2}[a-z]?\\b[^)]*\\)");
    /** A numeric reference marker, e.g. "[3]", "[3, 4]", or "[3-5]". */
    private static final Pattern NUMERIC_CITATION = Pattern.compile("\\[\\d+(?:\\s*[-,]\\s*\\d+)*\\]");
    /** A URL. */
    private static final Pattern URL = Pattern.compile("(?:https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);
    /** A DOI. */
    private static final Pattern DOI = Pattern.compile("\\b10\\.\\d{4,}/\\S+");
    /** A sentence counts as a quotation only if at least this fraction of its characters are inside quotes. */
    private static final double QUOTED_FRACTION = 0.6;

    private CitationDetector() {
    }

    /**
     * The outcome of an attribution check.
     * @param status the attribution status.
     * @param evidence the matched quote/citation text, or {@code null} if unattributed.
     */
    public record AttributionCheck(AttributionStatus status, String evidence) {
    }

    /**
     * Classifies the attribution of a sentence.
     * @param sentence the query sentence text.
     * @return the attribution check (status and supporting evidence).
     */
    public static AttributionCheck detect(String sentence) {
        if (isQuoted(sentence)) {
            return new AttributionCheck(AttributionStatus.QUOTED, "quotation marks");
        }
        for (Pattern pattern : new Pattern[] {URL, DOI, PARENTHETICAL_CITATION, NUMERIC_CITATION}) {
            Matcher matcher = pattern.matcher(sentence);
            if (matcher.find()) {
                return new AttributionCheck(AttributionStatus.CITED, matcher.group());
            }
        }
        return new AttributionCheck(AttributionStatus.UNATTRIBUTED, null);
    }

    /**
     * True if most of the sentence is enclosed in quotation marks, i.e. it quotes borrowed material rather than merely
     * containing an incidental quoted phrase or dialogue. Considers straight, curly, and guillemet quotes.
     */
    private static boolean isQuoted(String sentence) {
        if (sentence.isEmpty()) {
            return false;
        }
        int quoted = Math.max(toggledQuotedLength(sentence),
                Math.max(pairedQuotedLength(sentence, '“', '”'), pairedQuotedLength(sentence, '«', '»')));
        return quoted >= QUOTED_FRACTION * sentence.length();
    }

    /** Characters enclosed by matched pairs of the straight quote ("). Unclosed trailing quotes contribute nothing. */
    private static int toggledQuotedLength(String sentence) {
        int count = 0;
        int start = -1;
        for (int i = 0; i < sentence.length(); i++) {
            if (sentence.charAt(i) == '"') {
                if (start < 0) {
                    start = i;
                } else {
                    count += i - start - 1;
                    start = -1;
                }
            }
        }
        return count;
    }

    /** Characters enclosed by matched open/close quote pairs (e.g. curly or guillemet quotes). */
    private static int pairedQuotedLength(String sentence, char open, char close) {
        int count = 0;
        int start = -1;
        for (int i = 0; i < sentence.length(); i++) {
            char character = sentence.charAt(i);
            if (character == open && start < 0) {
                start = i;
            } else if (character == close && start >= 0) {
                count += i - start - 1;
                start = -1;
            }
        }
        return count;
    }
}
