package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Tests sentence attribution, category classification, and HTML rendering with a stub sentence embedder ({@code |}-
 * delimited sentences, one-hot vectors by marker word), so no neural model is loaded.
 */
class OriginalityReportGeneratorTest {

    /**
     * Splits on '|'; a sentence containing "alpha" gets vector [1,0], otherwise [0,1]. Each sentence carries its character
     * range in the text, as the real splitter's do, so the report can render the document rather than the sentence list.
     */
    private static final Function<String, List<EmbeddedSentence>> STUB = text -> {
        List<EmbeddedSentence> sentences = new ArrayList<>();
        int cursor = 0;
        for (String part : text.split("\\|", -1)) {
            String sentence = part.strip();
            if (!sentence.isEmpty()) {
                int begin = cursor + part.length() - part.stripLeading().length();
                float[] vector = sentence.contains("alpha") ? new float[] {1, 0} : new float[] {0, 1};
                sentences.add(new EmbeddedSentence(sentence, vector, begin, begin + sentence.length()));
            }
            cursor += part.length() + 1; // past the part and the '|' that followed it
        }
        return sentences;
    };

    private static OriginalityReportGenerator generator(double threshold) {
        return new OriginalityReportGenerator(threshold, 0, STUB, false);
    }

    /** A candidate source with an unknown author. */
    private static ArchivedDocument source(String id, String text) {
        return new ArchivedDocument(id, Set.of(), text);
    }

    /** Runs a report for a query with no known author. */
    private static String report(OriginalityReportGenerator generator, String queryText, List<ArchivedDocument> sources) {
        return generator.generate("q", queryText, sources, Set.of());
    }

    /** Asserts a matched sentence is highlighted with the given category's colour (not just the legend swatch). */
    private static boolean highlightsWithCategory(String html, MatchCategory category) {
        return html.contains("class=\"match\" style=\"background:" + category.colour());
    }

    @Test
    void testHighlightsMatchedSentenceAndScoresHalf() {
        // one matching sentence (alpha, 3 words) + one non-matching (3 words) -> 50% of words matched
        String html = report(generator(0.9), "alpha sentence here | totally different clause", List.of(source("source-a", "alpha thing")));

        assertTrue(html.contains("class=\"match\""), "The matched sentence should be highlighted");
        assertTrue(html.contains("source-a"), "The source should appear in the overview");
        assertTrue(html.contains("50%"), "Half the words matched -> 50% similarity");
    }

    @Test
    void testWordOverlapGateDropsAMatchThatSharesOnlyItsMeaning() {
        // Semantically identical but differently worded: reuse between two students, but merely the same standard fact
        // when the source is a textbook. A word-overlap floor keeps the second kind out without moving the threshold.
        String query = "alpha quick brown fox";
        List<ArchivedDocument> sources = List.of(source("s", "alpha lazy grey hound"));

        assertTrue(report(generator(0.9), query, sources).contains("class=\"match\""), "Without a floor the paraphrase matches");

        OriginalityReportGenerator gated = new OriginalityReportGenerator(0.9, 0.8, STUB, false);
        String html = report(gated, query, sources);
        assertFalse(html.contains("class=\"match\""), "Sharing only the meaning should not survive a word-overlap floor");
        assertTrue(html.contains("No matching sources found"), "and the source should not be credited either");
    }

    @Test
    void testWordOverlapGateKeepsVerbatimReuse() {
        OriginalityReportGenerator gated = new OriginalityReportGenerator(0.9, 0.8, STUB, false);
        String html = report(gated, "alpha quick brown fox", List.of(source("s", "alpha quick brown fox")));

        assertTrue(highlightsWithCategory(html, MatchCategory.COPY_PASTE), "Identical wording clears any floor");
    }

    @Test
    void testCopyPasteWhenWordingIsIdentical() {
        String html = report(generator(0.9), "alpha quick brown fox", List.of(source("s", "alpha quick brown fox")));
        assertTrue(highlightsWithCategory(html, MatchCategory.COPY_PASTE), "Identical wording should be classified as copy-paste");
    }

    @Test
    void testParaphraseWhenMeaningMatchesButWordsDiffer() {
        // Same marker (so cosine matches) but almost no shared words -> paraphrase.
        String html = report(generator(0.9), "alpha quick brown fox", List.of(source("s", "alpha lazy green turtle")));
        assertTrue(highlightsWithCategory(html, MatchCategory.PARAPHRASE), "Same meaning, different words should be paraphrase");
    }

    @Test
    void testCitedMatchIsAttributedAndDeEmphasized() {
        String html = report(generator(0.9), "alpha copied line (Smith, 2020)", List.of(source("s", "alpha copied line")));
        assertTrue(html.contains("class=\"match attributed\""), "A cited match should be marked attributed (de-emphasized)");
    }

    @Test
    void testTrailingCitationInNextSentenceAttributesTheMatch() {
        // The copied sentence and its citation are split into two sentences (as CoreNLP would after a period).
        String html = report(generator(0.9), "alpha copied line|(Smith, 2020)", List.of(source("s", "alpha copied line")));
        assertTrue(html.contains("class=\"match attributed\""), "A citation in the following sentence should attribute the match");
    }

    @Test
    void testUncitedMatchIsUnattributed() {
        String html = report(generator(0.9), "alpha copied line with no source", List.of(source("s", "alpha copied line")));
        assertTrue(html.contains("class=\"match\" style="), "An uncited match should stay a plain (unattributed) highlight");
        assertFalse(html.contains("class=\"match attributed\""), "It must not be marked attributed");
    }

    @Test
    void testExcludeAttributedHidesCitedMatchesButKeepsConcerns() {
        String query = "alpha cited copy (Smith, 2020)|alpha bare copy here";
        List<ArchivedDocument> sources = List.of(source("s", "alpha thing"));

        String shown = new OriginalityReportGenerator(0.9, 0, STUB, false).generate("q", query, sources, Set.of());
        String hidden = new OriginalityReportGenerator(0.9, 0, STUB, true).generate("q", query, sources, Set.of());

        assertTrue(shown.contains("class=\"match attributed\""), "Without exclude-attributed a cited match is shown (de-emphasized)");
        assertFalse(hidden.contains("class=\"match attributed\""), "With exclude-attributed, cited matches are not highlighted");
        assertTrue(hidden.contains("class=\"match\" style="), "Unattributed matches are still highlighted");
    }

    @Test
    void testSelfReuseMarkedWhenSourceAuthorMatchesQueryAuthor() {
        OriginalityReportGenerator generator = generator(0.9);
        String self = generator.generate("q", "alpha copied line", List.of(new ArchivedDocument("prior", Set.of("alice"), "alpha copied line")),
                Set.of("alice"));
        String other = generator.generate("q", "alpha copied line", List.of(new ArchivedDocument("prior", Set.of("bob"), "alpha copied line")),
                Set.of("alice"));

        assertTrue(self.contains("class=\"match self\""), "A match to the same author's work is self-reuse");
        assertTrue(self.contains("Self-reuse"), "The legend should report self-reuse");
        assertFalse(other.contains("class=\"match self\""), "A match to another author's work is not self-reuse");
    }

    @Test
    void testSelfCopyIsLightPurpleAndExcludedFromCopyPaste() {
        // Identical wording to the same author's prior work: a self-copy (copy-paste level word overlap).
        List<ArchivedDocument> priorWork = List.of(new ArchivedDocument("prior", Set.of("alice"), "alpha copied line"));
        String html = generator(0.9).generate("q", "alpha copied line", priorWork, Set.of("alice"));

        assertTrue(html.contains("class=\"match self\" style=\"background:#e1bee7"),
                "A self-copy should be highlighted in light purple, not the copy-paste colour");
        assertTrue(html.contains("Copy-paste <b>0%</b>"), "A self-copy must not be counted in the copy-paste category");
        assertTrue(html.contains("Self-reuse (own prior work) <b>100%</b>"), "It is counted as self-reuse instead");
    }

    @Test
    void testNoMatchesYieldsNoHighlights() {
        String html = report(generator(0.99), "beta one two|gamma three four", List.of(source("s", "alpha only")));
        assertTrue(html.contains("No matching sources found."), "With no matches the overview should say so");
        assertFalse(html.contains("class=\"match\""), "Nothing should be highlighted");
        assertFalse(html.contains("Matched source passages"), "Without matches there is no passage section");
    }

    @Test
    void testEachSentenceIsAttributedToItsSingleBestSource() {
        // Two sources carry the passage equally well. It must be credited to one of them only, or the percentages would
        // double-count the same words and a document could exceed 100% similarity.
        List<ArchivedDocument> sources = List.of(source("first", "alpha copied line"), source("second", "alpha copied line"));
        String html = report(generator(0.9), "alpha copied line", sources);

        assertEquals(1, html.split("class=\"pct\"", -1).length - 1, "Exactly one source should be credited for the sentence");
        assertTrue(html.contains("<span class=\"pct\">100%</span>"), "That source accounts for the whole document, not 200%");
    }

    @Test
    void testSourceSentencesAreEmbeddedOnceAcrossReports() {
        // Every query is compared against the same pool of sources, so re-embedding a source per query would be quadratic
        // in the corpus size - and embedding, not comparing, is what a run spends its time on.
        Map<String, Integer> embedCalls = new HashMap<>();
        Function<String, List<EmbeddedSentence>> counting = text -> {
            embedCalls.merge(text, 1, Integer::sum);
            return STUB.apply(text);
        };
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, 0, counting, false);
        List<ArchivedDocument> sources = List.of(source("shared", "alpha shared source"));

        generator.generate("q1", "alpha first query", sources, Set.of());
        generator.generate("q2", "alpha second query", sources, Set.of());

        assertEquals(1, embedCalls.get("alpha shared source"), "The shared source should be embedded once, not once per query");
        assertEquals(1, embedCalls.get("alpha first query"), "Each query's own text is embedded once");
        assertEquals(1, embedCalls.get("alpha second query"), "Each query's own text is embedded once");
    }

    @Test
    void testEverySentenceIsCheckedAndCounted() {
        // Nothing is held back from the check: a reference-list entry matching a source is a match like any other, and
        // the percentages are shares of the whole document.
        String query = "alpha copied body line|a body line unmatched|References|alpha Boas H 2025 Social Theory and Health";
        String html = report(generator(0.9), query, List.of(source("s", "alpha thing")));

        assertEquals(2, html.split("class=\"match", -1).length - 1, "Both the body line and the reference entry should match");
        assertFalse(html.contains("class=\"skipped\""), "No sentence should be left out of the check");
    }

    @Test
    void testDocumentIsRenderedAsSubmittedIncludingWhatIsNeverChecked() {
        // The report is the reader's copy of the document, so everything between the checked sentences has to reach them:
        // the cover sheet, the headings, the blank lines. Re-joining the sentences the splitter produced loses all of it,
        // and a document that arrives as one running block reads as a defect in the tool.
        Function<String, List<EmbeddedSentence>> skippingShortLines = text -> STUB.apply(text).stream()
                .filter(sentence -> sentence.text().split("\\s+").length >= 3).toList();
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, 0, skippingShortLines, false);

        String query = "Coversheet 240026012|Introduction|alpha copied line here|\n\nA closing note that is longer.";
        String html = generator.generate("q", query, List.of(source("s", "alpha thing here")), Set.of());
        String main = html.substring(html.indexOf("<main>") + 6, html.indexOf("</main>"));

        assertTrue(main.contains("Coversheet 240026012"), "A cover sheet is too short to be checked but is part of the document");
        assertTrue(main.contains("Introduction"), "A heading is never a sentence, and must still be rendered");
        assertTrue(main.contains("\n\n"), "The blank line between two paragraphs has to survive into the report");
        assertTrue(html.contains("white-space:pre-wrap"), "and the stylesheet has to render it rather than collapse it");
        assertTrue(main.contains("class=\"match\""), "the matched sentence is still highlighted in place");
    }

    @Test
    void testPercentagesAreSharesOfTheWholeDocument() {
        // Four of the document's eight words are matched. The other four sit in lines too short to be checked at all -
        // never candidates for a match, but words the reader can see, so they belong in the denominator.
        Function<String, List<EmbeddedSentence>> skippingShortLines = text -> STUB.apply(text).stream()
                .filter(sentence -> sentence.text().split("\\s+").length >= 3).toList();
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, 0, skippingShortLines, false);

        String query = "Coversheet 240026012 | Introduction | alpha copied line here | End";
        String html = generator.generate("q", query, List.of(source("s", "alpha thing here")), Set.of());

        assertTrue(html.contains("<span class=\"pct\">50%</span>"), "4 matched words out of the document's 8, not out of the 4 checked");
    }

    @Test
    void testHighlightLinksToTheMatchedSourcePassageAndBack() {
        // The source's second sentence (index 1) is the matching one; its first stays unmatched context.
        String html = report(generator(0.9), "alpha sentence here", List.of(source("source-a", "unrelated beta text|alpha thing")));

        assertTrue(html.contains("id=\"q0\"") && html.contains("href=\"#m-1-1\""), "The highlight should link to the matched source sentence");
        assertTrue(html.contains("Matched source passages"), "The source's text should be rendered below the report");
        assertTrue(html.contains("<a class=\"hit\" id=\"m-1-1\" href=\"#q0\""), "The matched passage should be anchored and link back");
        assertTrue(html.contains(">alpha thing</a>"), "The matched source sentence should be the highlighted passage");
        assertTrue(html.contains("<span>unrelated beta text</span>"), "Unmatched source sentences should render as plain context");
        assertTrue(html.contains("id=\"src-1\""), "The sidebar's source entry should be able to link to the passage block");
    }
}
