package de.jplag.text.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a document's sentences into its cover sheet, its body, and its reference list, so that only the body is
 * checked for reuse.
 * <p>
 * Front matter and reference lists are the two places where every submission in a cohort legitimately says the same
 * thing: cover sheets repeat the module title, the word count and the university's good-academic-practice declaration
 * verbatim, and reference lists repeat the citations of a shared reading list. Neither is the student's own prose, so a
 * match there is not evidence of anything. {@link CitationDetector} cannot catch the reference list on its own because
 * sentence splitting shreds each entry — "Boas, H. (2025). Are organ donations expressions of altruism? Social Theory
 * &amp; Health, 23(1)." becomes three sentences and only the ones carrying the year and the DOI look like citations.
 * Working at the section level catches the whole block instead.
 */
public final class DocumentSections {

    /** Where a sentence sits in a document. */
    public enum Section {

        /** Cover sheet, title page, declaration: the administrative preamble. */
        FRONT_MATTER("front matter"),
        /** The student's own prose: the only part checked for reuse. */
        BODY("body"),
        /** The reference list / bibliography and anything after it. */
        REFERENCES("references");

        private final String label;

        Section(String label) {
            this.label = label;
        }

        /**
         * @return the human-readable label.
         */
        public String label() {
            return label;
        }
    }

    /** A reference-list heading, allowing for a leading number/bullet, e.g. "4. Bibliography:". */
    private static final Pattern REFERENCE_HEADING = Pattern.compile(
            "^[\\s\\d.)\\-–—*•]{0,10}(references|reference list|list of references|bibliography|works cited|primary sources|secondary sources)\\b",
            Pattern.CASE_INSENSITIVE);
    /**
     * Shapes that belong to a bibliography entry rather than to prose: a DOI or URL, an "Surname, I." author, a volume and
     * issue, or volume/number/page abbreviations. Deliberately not the parenthetical "(Smith, 2020)" of a cited sentence,
     * which is prose and must stay checkable.
     */
    private static final Pattern ENTRY_MARKER = Pattern.compile("(?:https?://|www\\.|\\b10\\.\\d{4,}/)|^\\s*[\\p{Lu}][\\p{L}'’-]+,\\s+\\p{Lu}"
            + "|\\bvol\\.|\\bno\\.\\s*\\d|\\bpp?\\.\\s*\\d|\\b\\d+\\s*\\(\\d+\\)");
    /** Wording that only appears on a cover sheet, title page, or academic-practice declaration. */
    private static final Pattern COVER_SHEET_MARKER = Pattern.compile("\\b(cover ?sheet|module (code|title|name)|matriculation|student ?id"
            + "|id ?(no|number)|word ?count|words in length|deadline|date submitted|tutor|supervisor|seminar|degree programme|academic year"
            + "|good academic practice|i (hereby )?(declare|certify|confirm)|we (hereby )?(declare|certify|confirm)|this (is|work) is my own"
            + "|excluding (the )?(title|bibliograph|reference))\\b", Pattern.CASE_INSENSITIVE);

    /** A reference heading before this share of the document is a table-of-contents entry, not the list itself. */
    private static final double REFERENCES_EARLIEST_POSITION = 0.30;
    /** Front matter is only looked for in this leading share of a document. */
    private static final double FRONT_MATTER_LATEST_POSITION = 0.20;
    /** Sentences always searched for front matter, however short the document: a cover sheet is a fixed size. */
    private static final int FRONT_MATTER_MINIMUM_WINDOW = 10;
    /** Cover-sheet markers needed before a prefix is treated as front matter; one alone is likely incidental. */
    private static final int MINIMUM_COVER_SHEET_MARKERS = 2;
    /** Consecutive sentences that must look like bibliography entries before the run counts as a reference block. */
    private static final int MINIMUM_ENTRY_RUN = 3;
    /** Share of a run that must carry an entry marker; the rest are the article titles between them. */
    private static final double MINIMUM_ENTRY_DENSITY = 0.5;

    /** Share of a sentence's words that may carry a digit before it is data rather than prose. */
    private static final double MAXIMUM_NUMERIC_SHARE = 0.30;

    private DocumentSections() {
        // utility class
    }

    /**
     * Whether a sentence is a table row, a data readout, or a citation stub rather than something anyone wrote as a
     * sentence. Sentence splitting flattens a table into "sentences" of column headings and figures; two students who
     * measured the same thing in the same practical produce nearly the same row, which is neither surprising nor reuse.
     * @param sentence the sentence.
     * @return whether the sentence is data rather than prose.
     */
    public static boolean isTabular(String sentence) {
        String[] words = sentence.trim().split("\\s+");
        if (words.length == 0) {
            return false;
        }
        long numeric = 0;
        for (String word : words) {
            if (word.chars().anyMatch(Character::isDigit)) {
                numeric++;
            }
        }
        return numeric >= MAXIMUM_NUMERIC_SHARE * words.length;
    }

    /**
     * Assigns every sentence of a document to a section.
     * @param sentences the document's sentences, in order.
     * @return the section of each sentence, in the same order.
     */
    public static List<Section> of(List<String> sentences) {
        int count = sentences.size();
        int referencesFrom = referencesStart(sentences);
        int frontMatterUntil = frontMatterEnd(sentences, referencesFrom);
        List<Section> sections = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            sections.add(i <= frontMatterUntil ? Section.FRONT_MATTER : i >= referencesFrom ? Section.REFERENCES : Section.BODY);
        }
        markEntryRuns(sentences, sections);
        return sections;
    }

    /**
     * Marks stretches of bibliography entries that no heading announced. Not every reference list is a section: on a
     * worksheet it is the answer to a numbered question, and in a footnoted essay it is a block at the end of a page. A run
     * of entries is recognisable without a heading because bibliographic shapes cluster — and the article titles between
     * them, which look like ordinary prose on their own, are carried along by the run they sit in.
     */
    private static void markEntryRuns(List<String> sentences, List<Section> sections) {
        int start = 0;
        while (start < sentences.size()) {
            if (!ENTRY_MARKER.matcher(sentences.get(start)).find()) {
                start++;
                continue;
            }
            int end = start;
            int markers = 0;
            for (int i = start; i < sentences.size(); i++) {
                boolean marker = ENTRY_MARKER.matcher(sentences.get(i)).find();
                if (marker) {
                    markers++;
                    end = i;
                } else if (i - end > 1) {
                    break; // two non-entry sentences in a row: the block has ended
                }
            }
            int length = end - start + 1;
            if (length >= MINIMUM_ENTRY_RUN && markers >= MINIMUM_ENTRY_DENSITY * length) {
                for (int i = start; i <= end; i++) {
                    if (sections.get(i) == Section.BODY) {
                        sections.set(i, Section.REFERENCES);
                    }
                }
            }
            start = end + 1;
        }
    }

    /** Index of the first sentence of the reference list, or the sentence count if the document has none. */
    private static int referencesStart(List<String> sentences) {
        int earliest = (int) Math.ceil(REFERENCES_EARLIEST_POSITION * sentences.size());
        for (int i = earliest; i < sentences.size(); i++) {
            if (REFERENCE_HEADING.matcher(sentences.get(i)).find()) {
                return i;
            }
        }
        return sentences.size();
    }

    /**
     * Index of the last sentence of the front matter, or -1 if the document has none. The front matter runs to the last
     * cover-sheet marker in the document's opening, since the declaration that ends a cover sheet is itself a marker.
     */
    private static int frontMatterEnd(List<String> sentences, int referencesFrom) {
        int window = Math.max((int) (FRONT_MATTER_LATEST_POSITION * sentences.size()), FRONT_MATTER_MINIMUM_WINDOW);
        int latest = Math.min(window, referencesFrom);
        int lastMarker = -1;
        int markers = 0;
        for (int i = 0; i < latest; i++) {
            if (COVER_SHEET_MARKER.matcher(sentences.get(i)).find()) {
                lastMarker = i;
                markers++;
            }
        }
        return markers >= MINIMUM_COVER_SHEET_MARKERS ? lastMarker : -1;
    }
}
