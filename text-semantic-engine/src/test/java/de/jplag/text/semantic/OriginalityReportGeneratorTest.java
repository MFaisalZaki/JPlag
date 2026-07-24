package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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
    void testUncitedMatchIsUnattributed() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, STUB);
        String html = generator.generate("q", "alpha copied line with no source", List.of(new ArchivedDocument("s", "alpha copied line")));
        assertTrue(html.contains("class=\"match\" style="), "An uncited match should stay a plain (unattributed) highlight");
        assertFalse(html.contains("class=\"match attributed\""), "It must not be marked attributed");
    }

    @Test
    void testNoMatchesYieldsNoHighlights() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.99, STUB);
        String html = generator.generate("q", "beta one two|gamma three four", List.of(new ArchivedDocument("s", "alpha only")));
        assertTrue(html.contains("No matching sources found."), "With no matches the overview should say so");
        assertFalse(html.contains("class=\"match\""), "Nothing should be highlighted");
    }
}
