package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Tests sentence attribution, category classification, and HTML rendering with a stub sentence embedder ({@code |}-
 * delimited sentences, one-hot vectors by marker word), so no neural model is loaded.
 */
class OriginalityReportGeneratorTest {

    /** Splits on '|'; a sentence containing "alpha" gets vector [1,0], otherwise [0,1]. */
    private static final Function<String, List<EmbeddedSentence>> STUB = text -> {
        List<EmbeddedSentence> sentences = new ArrayList<>();
        for (String sentence : text.split("\\|")) {
            float[] vector = sentence.contains("alpha") ? new float[] {1, 0} : new float[] {0, 1};
            sentences.add(new EmbeddedSentence(sentence.trim(), vector));
        }
        return sentences;
    };

    /** Asserts a matched sentence is highlighted with the given category's colour (not just the legend swatch). */
    private static boolean highlightsWithCategory(String html, MatchCategory category) {
        return html.contains("class=\"match\" style=\"background:" + category.colour());
    }

    @Test
    void testHighlightsMatchedSentenceAndScoresHalf() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, STUB);
        // one matching sentence (alpha, 3 words) + one non-matching (3 words) -> 50% of words matched
        String html = generator.generate("q", "alpha sentence here|totally different clause",
                List.of(new ArchivedDocument("source-a", "alpha thing")));

        assertTrue(html.contains("class=\"match\""), "The matched sentence should be highlighted");
        assertTrue(html.contains("source-a"), "The source should appear in the overview");
        assertTrue(html.contains("50%"), "Half the words matched -> 50% similarity");
    }

    @Test
    void testCopyPasteWhenWordingIsIdentical() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, STUB);
        String html = generator.generate("q", "alpha quick brown fox", List.of(new ArchivedDocument("s", "alpha quick brown fox")));
        assertTrue(highlightsWithCategory(html, MatchCategory.COPY_PASTE), "Identical wording should be classified as copy-paste");
    }

    @Test
    void testParaphraseWhenMeaningMatchesButWordsDiffer() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, STUB);
        // Same marker (so cosine matches) but almost no shared words -> paraphrase.
        String html = generator.generate("q", "alpha quick brown fox", List.of(new ArchivedDocument("s", "alpha lazy green turtle")));
        assertTrue(highlightsWithCategory(html, MatchCategory.PARAPHRASE), "Same meaning, different words should be paraphrase");
    }

    @Test
    void testCitedMatchIsAttributedAndDeEmphasized() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, STUB);
        String html = generator.generate("q", "alpha copied line (Smith, 2020)", List.of(new ArchivedDocument("s", "alpha copied line")));
        assertTrue(html.contains("class=\"match attributed\""), "A cited match should be marked attributed (de-emphasized)");
    }

    @Test
    void testTrailingCitationInNextSentenceAttributesTheMatch() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, STUB);
        // The copied sentence and its citation are split into two sentences (as CoreNLP would after a period).
        String html = generator.generate("q", "alpha copied line|(Smith, 2020)", List.of(new ArchivedDocument("s", "alpha copied line")));
        assertTrue(html.contains("class=\"match attributed\""), "A citation in the following sentence should attribute the match");
    }

    @Test
    void testUncitedMatchIsUnattributed() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, STUB);
        String html = generator.generate("q", "alpha copied line with no source", List.of(new ArchivedDocument("s", "alpha copied line")));
        assertTrue(html.contains("class=\"match\" style="), "An uncited match should stay a plain (unattributed) highlight");
        assertFalse(html.contains("class=\"match attributed\""), "It must not be marked attributed");
    }

    @Test
    void testExcludeAttributedHidesCitedMatchesButKeepsConcerns() {
        String query = "alpha cited copy (Smith, 2020)|alpha bare copy here";
        List<ArchivedDocument> source = List.of(new ArchivedDocument("s", "alpha thing"));

        String shown = new OriginalityReportGenerator(0.9, STUB, false).generate("q", query, source);
        String hidden = new OriginalityReportGenerator(0.9, STUB, true).generate("q", query, source);

        assertTrue(shown.contains("class=\"match attributed\""), "By default a cited match is shown (de-emphasized)");
        assertFalse(hidden.contains("class=\"match attributed\""), "With exclude-attributed, cited matches are not highlighted");
        assertTrue(hidden.contains("class=\"match\" style="), "Unattributed matches are still highlighted");
    }

    @Test
    void testSelfReuseMarkedWhenSourceAuthorMatchesQueryAuthor() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, STUB);
        List<ArchivedDocument> priorWork = List.of(new ArchivedDocument("prior", "alice", "alpha copied line"));
        String self = generator.generate("q", "alpha copied line", priorWork, "alice");
        String other = generator.generate("q", "alpha copied line", List.of(new ArchivedDocument("prior", "bob", "alpha copied line")), "alice");

        assertTrue(self.contains("class=\"match self\""), "A match to the same author's work is self-reuse");
        assertTrue(self.contains("Self-reuse"), "The legend should report self-reuse");
        assertFalse(other.contains("class=\"match self\""), "A match to another author's work is not self-reuse");
    }

    @Test
    void testSelfCopyIsLightPurpleAndExcludedFromCopyPaste() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, STUB);
        // Identical wording to the same author's prior work: a self-copy (copy-paste level word overlap).
        List<ArchivedDocument> priorWork = List.of(new ArchivedDocument("prior", "alice", "alpha copied line"));
        String html = generator.generate("q", "alpha copied line", priorWork, "alice");

        assertTrue(html.contains("class=\"match self\" style=\"background:#e1bee7"),
                "A self-copy should be highlighted in light purple, not the copy-paste colour");
        assertFalse(html.contains("dashed"), "The dashed self-reuse outline should be gone");
        assertTrue(html.contains("Copy-paste <b>0%</b>"), "A self-copy must not be counted in the copy-paste category");
        assertTrue(html.contains("Self-reuse (own prior work) <b>100%</b>"), "It is counted as self-reuse instead");
    }

    @Test
    void testNoMatchesYieldsNoHighlights() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.99, STUB);
        String html = generator.generate("q", "beta one two|gamma three four", List.of(new ArchivedDocument("s", "alpha only")));
        assertTrue(html.contains("No matching sources found."), "With no matches the overview should say so");
        assertFalse(html.contains("class=\"match\""), "Nothing should be highlighted");
        assertFalse(html.contains("Matched source passages"), "Without matches there is no passage section");
    }

    @Test
    void testPassageCommonToMostSourcesIsNotReported() {
        // The same passage appears in four of the five candidate sources, as a shared citation or a stock definition
        // would; it cannot have been taken from any one of them, so it is common material rather than reuse.
        List<ArchivedDocument> sources = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sources.add(new ArchivedDocument("s" + i, i < 4 ? "alpha shared boilerplate clause" : "beta unrelated filler text"));
        }
        String html = new OriginalityReportGenerator(0.9, STUB).generate("q", "alpha shared boilerplate clause", sources);

        assertFalse(html.contains("class=\"match\""), "A passage present in most sources should not be reported as reuse");
        assertTrue(html.contains("No matching sources found."), "With its only candidate filtered out, no source should be listed");
    }

    @Test
    void testPassageInFewSourcesIsStillReported() {
        // The same wording, but confined to one source out of five: that is reuse, and must survive the commonality check.
        List<ArchivedDocument> sources = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sources.add(new ArchivedDocument("s" + i, i == 0 ? "alpha shared boilerplate clause" : "beta unrelated filler text"));
        }
        String html = new OriginalityReportGenerator(0.9, STUB).generate("q", "alpha shared boilerplate clause", sources);

        assertTrue(html.contains("class=\"match\""), "A passage matching only one source is reuse and should be reported");
        assertTrue(html.contains("s0"), "It should be attributed to that source");
    }

    @Test
    void testSemanticMatchWithoutSharedWordingIsNotReported() {
        // Four documents, so word rarity is measurable. The query sentence embeds identically to its source sentence but
        // shares no wording with it: the same topic, not the same text.
        List<ArchivedDocument> sources = List.of(new ArchivedDocument("s0", "alpha entirely separate vocabulary"),
                new ArchivedDocument("s1", "beta padding one"), new ArchivedDocument("s2", "beta padding two"));
        String html = new OriginalityReportGenerator(0.9, STUB).generate("q", "alpha unrelated distinct phrasing", sources);

        assertFalse(html.contains("class=\"match\""), "Similarity with no shared wording is not evidence of reuse");
        assertTrue(html.contains("Paraphrase <b>0%</b>"), "Nothing should be counted towards the score");
    }

    @Test
    void testDisablingTheLexicalRequirementRestoresSemanticOnlyMatching() {
        List<ArchivedDocument> sources = List.of(new ArchivedDocument("s0", "alpha entirely separate vocabulary"),
                new ArchivedDocument("s1", "beta padding one"), new ArchivedDocument("s2", "beta padding two"));
        String html = new OriginalityReportGenerator(0.9, STUB, false, 0.0, 1.0, true).generate("q", "alpha unrelated distinct phrasing", sources);

        assertTrue(html.contains("class=\"match\""), "With the lexical requirement at zero, cosine similarity alone matches again");
    }

    @Test
    void testCoverSheetIsExcludedFromMatchingAndFromTheWordTotal() {
        // Both documents open with the same cover sheet; only the second sentence is the author's own writing.
        String coverSheet = "STUDENT ID No: 240015513 MODULE CODE: SD2005 WORD COUNT: 788";
        String html = new OriginalityReportGenerator(0.9, STUB).generate("q", coverSheet + "|alpha genuine copied line",
                List.of(new ArchivedDocument("s", coverSheet + "|alpha genuine copied line")));

        assertTrue(html.contains("<span>" + coverSheet + "</span>"), "The cover sheet should render as plain context, not as a match");
        // The remaining sentence is the whole of the analysed text, so a match to it is 100% - not diluted by the cover
        // sheet's 10 words, which would otherwise put it at 29%.
        assertTrue(html.contains("Copy-paste <b>100%</b>"), "Front-matter words must not count towards the word total");
    }

    @Test
    void testIntegrityDeclarationIsExcluded() {
        String declaration = "I have read the University's Statement on Good Academic Practice and this work is my own";
        String html = new OriginalityReportGenerator(0.9, STUB).generate("q", "alpha real content here|" + declaration,
                List.of(new ArchivedDocument("s", "alpha real content here|" + declaration)));

        assertTrue(html.contains("<span>" + declaration + "</span>"), "The declaration should render as plain context, not as a match");
        assertTrue(html.contains("Copy-paste <b>100%</b>"), "Only the author's own writing should be scored");
    }

    @Test
    void testIncludingBoilerplateRestoresTheOldBehaviour() {
        String coverSheet = "STUDENT ID No: 240015513 MODULE CODE: SD2005 WORD COUNT: 788";
        String html = new OriginalityReportGenerator(0.9, STUB, false, 0.0, 1.0, false).generate("q", coverSheet,
                List.of(new ArchivedDocument("s", coverSheet)));

        assertTrue(html.contains("class=\"match\""), "With boilerplate included, the cover sheet matches as before");
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
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, counting);
        List<ArchivedDocument> sources = List.of(new ArchivedDocument("shared", "alpha shared source"));

        generator.generate("q1", "alpha first query", sources);
        generator.generate("q2", "alpha second query", sources);

        assertEquals(1, embedCalls.get("alpha shared source"), "The shared source should be embedded once, not once per query");
        assertEquals(1, embedCalls.get("alpha first query"), "Each query's own text is embedded once");
        assertEquals(1, embedCalls.get("alpha second query"), "Each query's own text is embedded once");
    }

    @Test
    void testHighlightLinksToTheMatchedSourcePassageAndBack() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, STUB);
        // The source's second sentence (index 1) is the matching one; its first stays unmatched context.
        String html = generator.generate("q", "alpha sentence here", List.of(new ArchivedDocument("source-a", "unrelated beta text|alpha thing")));

        assertTrue(html.contains("id=\"q0\"") && html.contains("href=\"#m-1-1\""), "The highlight should link to the matched source sentence");
        assertTrue(html.contains("Matched source passages"), "The source's text should be rendered below the report");
        assertTrue(html.contains("<a class=\"hit\" id=\"m-1-1\" href=\"#q0\""), "The matched passage should be anchored and link back");
        assertTrue(html.contains(">alpha thing</a>"), "The matched source sentence should be the highlighted passage");
        assertTrue(html.contains("<span>unrelated beta text</span>"), "Unmatched source sentences should render as plain context");
        assertTrue(html.contains("id=\"src-1\""), "The sidebar's source entry should be able to link to the passage block");
    }
}
