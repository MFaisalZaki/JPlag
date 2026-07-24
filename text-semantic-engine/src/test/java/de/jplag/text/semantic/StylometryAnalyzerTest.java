package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests that Burrows's Delta attributes a document to the candidate whose function-word style it shares, using two
 * synthetic authors with deliberately different style words.
 */
class StylometryAnalyzerTest {

    // "Formal" style: heavy on however/therefore/moreover/the.
    private static final String FORMAL = "the theory however is sound therefore the result holds moreover the data confirms the model ".repeat(15);
    // "Casual" style: heavy on so/really/just/a/i.
    private static final String CASUAL = "so i really just like a thing and a lot of stuff so really just kind of it ".repeat(15);

    private final StylometryAnalyzer analyzer = new StylometryAnalyzer(40);
    private final Map<String, String> authors = Map.of("formal", FORMAL, "casual", CASUAL);

    @Test
    void testAttributesFormalQueryToFormalAuthor() {
        String query = "the study however is robust therefore the finding holds moreover the evidence supports the claim ".repeat(6);
        List<StylometryAnalyzer.AuthorScore> ranked = analyzer.rank(authors, query);
        assertEquals("formal", ranked.get(0).author(), "A formal-style query should be closest to the formal author");
    }

    @Test
    void testAttributesCasualQueryToCasualAuthor() {
        String query = "so i really just want a bit of a break so really just kind of tired of it ".repeat(6);
        List<StylometryAnalyzer.AuthorScore> ranked = analyzer.rank(authors, query);
        assertEquals("casual", ranked.get(0).author(), "A casual-style query should be closest to the casual author");
    }

    @Test
    void testAllCandidatesAreRankedWithNonNegativeDelta() {
        List<StylometryAnalyzer.AuthorScore> ranked = analyzer.rank(authors, FORMAL);
        assertEquals(2, ranked.size());
        assertTrue(ranked.get(0).delta() <= ranked.get(1).delta(), "Results must be sorted by ascending Delta");
        assertTrue(ranked.stream().allMatch(score -> score.delta() >= 0.0), "Delta is a distance and cannot be negative");
    }
}
