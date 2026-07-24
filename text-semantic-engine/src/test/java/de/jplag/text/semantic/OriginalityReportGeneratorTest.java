package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Tests sentence attribution and HTML rendering with a stub sentence embedder ({@code |}-delimited sentences, one-hot
 * vectors by marker word), so no neural model is loaded.
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

    @Test
    void testHighlightsMatchedSentenceAndScoresHalf() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.9, STUB);
        // query: one matching sentence (alpha, 2 words) + one non-matching (2 words) -> 50% of words matched
        String html = generator.generate("q", "alpha sentence here|totally different clause",
                List.of(new ArchivedDocument("source-a", "alpha thing")));

        assertTrue(html.contains("class=\"match\""), "The matched sentence should be highlighted");
        assertTrue(html.contains("source-a"), "The source should appear in the overview");
        assertTrue(html.contains(">50%<") || html.contains("50%"), "Half the words matched -> 50% similarity");
    }

    @Test
    void testNoMatchesYieldsZeroAndNoSources() {
        OriginalityReportGenerator generator = new OriginalityReportGenerator(0.99, STUB);
        String html = generator.generate("q", "beta one two|gamma three four", List.of(new ArchivedDocument("source-a", "alpha only")));

        assertTrue(html.contains("No matching sources found."), "With no matches the overview should say so");
        assertFalse(html.contains("class=\"match\""), "Nothing should be highlighted");
    }
}
