package de.jplag.text.semantic;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Heuristically recognises administrative front matter — assignment cover sheets and academic-integrity declarations —
 * so it can be excluded from an originality report.
 * <p>
 * Such text is supplied by the institution and is therefore identical across every submission in a cohort. Left in, it
 * matches at near-perfect similarity and dominates the report: in one 26-submission cohort every match scoring above
 * 95% was the shared cover sheet. It is excluded from both the matching and the word total, so it neither creates
 * matches nor dilutes the percentage.
 * <p>
 * Two families are recognised. <em>Labels</em> are the {@code "Field:"} headings of a cover sheet ("Student ID:",
 * "Module Code:", "Word Count:"); because a single such phrase could occur in genuine prose, a passage needs two
 * distinct labels unless it is short enough to be a heading rather than a sentence. <em>Declarations</em> are the fixed
 * phrases of an academic-integrity statement, which are distinctive enough to match on their own.
 */
public final class BoilerplateDetector {

    /** Word count up to which a single administrative label is enough: a heading, not a sentence that mentions one. */
    private static final int SHORT_PASSAGE_WORDS = 12;

    /** Distinct administrative labels required before a longer passage counts as a cover sheet. */
    private static final int LABELS_REQUIRED = 2;

    /**
     * Cover-sheet field headings. Each requires a following colon, so prose that merely uses the words (e.g. "the module
     * title suggests") does not match.
     */
    private static final List<Pattern> LABELS = List.of(Pattern.compile("\\bstudent\\s*(?:id|number|no)\\b[^:\\n]{0,12}:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmatriculation\\s*(?:number|no)?\\b[^:\\n]{0,12}:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmodule\\s*(?:code|title|name)\\b\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcourse\\s*(?:code|title|name)\\b\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:assignment|assessment|coursework)\\s*(?:title|name|number|no)?\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdegree\\s*(?:programme|program)\\b\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bword\\s*count\\b\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:deadline|submission|due)\\s*date\\b\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:tutor|supervisor|seminar\\s*group|tutorial\\s*group)\\b\\s*:", Pattern.CASE_INSENSITIVE));

    /** Fixed phrases of an academic-integrity declaration; distinctive enough to match on their own. */
    private static final List<Pattern> DECLARATIONS = List.of(Pattern.compile("statement on good academic practice", Pattern.CASE_INSENSITIVE),
            Pattern.compile("in submitting this (?:assignment|work|coursework)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:hereby|do) (?:confirm|declare|certify)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:following work|this work|this assignment) is my own", Pattern.CASE_INSENSITIVE),
            Pattern.compile("properly acknowledged and referenced", Pattern.CASE_INSENSITIVE),
            Pattern.compile("academic debts and borrowings", Pattern.CASE_INSENSITIVE),
            Pattern.compile("i have read the university", Pattern.CASE_INSENSITIVE));

    /** Institutional headings, which carry no colon and so are recognised only in an all-capitals heading. */
    private static final Pattern INSTITUTION = Pattern.compile("\\b(?:school|faculty|department|college|division)\\s+of\\b",
            Pattern.CASE_INSENSITIVE);

    private BoilerplateDetector() {
    }

    /**
     * Determines whether a passage is administrative front matter rather than the author's own writing.
     * @param passage the passage text, typically one sentence as produced by the sentence splitter.
     * @return true if the passage should be excluded from the originality report.
     */
    public static boolean isBoilerplate(String passage) {
        if (passage == null || passage.isBlank()) {
            return false;
        }
        for (Pattern declaration : DECLARATIONS) {
            if (declaration.matcher(passage).find()) {
                return true;
            }
        }
        int words = passage.trim().split("\\s+").length;
        if (isCapitalisedHeading(passage, words) && INSTITUTION.matcher(passage).find()) {
            return true;
        }
        int labels = 0;
        for (Pattern label : LABELS) {
            if (label.matcher(passage).find()) {
                labels++;
            }
        }
        return labels >= LABELS_REQUIRED || (labels == 1 && words <= SHORT_PASSAGE_WORDS);
    }

    /** Whether the passage looks like a short all-capitals heading (PDF cover sheets set their headings this way). */
    private static boolean isCapitalisedHeading(String passage, int words) {
        return words <= SHORT_PASSAGE_WORDS && passage.equals(passage.toUpperCase(Locale.ROOT));
    }
}
