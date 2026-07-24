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

    /** True if the sentence contains a pair of double/curly/guillemet quotation marks. */
    private static boolean isQuoted(String sentence) {
        int straightQuotes = 0;
        for (int i = 0; i < sentence.length(); i++) {
            if (sentence.charAt(i) == '"') {
                straightQuotes++;
            }
        }
        boolean curly = sentence.indexOf('“') >= 0 && sentence.indexOf('”') >= 0;
        boolean guillemets = sentence.indexOf('«') >= 0 && sentence.indexOf('»') >= 0;
        return straightQuotes >= 2 || curly || guillemets;
    }
}
