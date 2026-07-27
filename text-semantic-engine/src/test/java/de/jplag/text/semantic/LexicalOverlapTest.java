package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests that shared wording is scored by how distinctive it is: vocabulary common to every document counts for nothing,
 * while rare wording counts for a lot.
 */
class LexicalOverlapTest {

    /** Five documents all about biodiversity; only the first two share the rare word "polyculture". */
    private static final List<String> CORPUS = List.of("biodiversity and food security depend on polyculture planting",
            "polyculture planting supports biodiversity and food security", "biodiversity and food security are linked",
            "food security requires biodiversity above all", "biodiversity underpins food security everywhere");

    @Test
    void testVocabularySharedByEveryDocumentCountsForNothing() {
        LexicalOverlap overlap = LexicalOverlap.fromDocuments(CORPUS);
        // Both sentences are built only from "biodiversity", "food" and "security", which every document contains.
        assertEquals(0.0, overlap.score("biodiversity food security", "food security biodiversity"), 1e-9,
                "Words common to every document carry no evidential weight");
    }

    @Test
    void testRareSharedWordingScoresHighly() {
        LexicalOverlap overlap = LexicalOverlap.fromDocuments(CORPUS);
        double score = overlap.score("polyculture planting biodiversity", "polyculture planting food security");
        assertTrue(score > 0.9, "Wording rare across the corpus is strong evidence, but scored " + score);
    }

    @Test
    void testDistinctiveWordingOutweighsGenericWording() {
        LexicalOverlap overlap = LexicalOverlap.fromDocuments(CORPUS);
        double distinctive = overlap.score("polyculture planting", "polyculture planting");
        double generic = overlap.score("biodiversity food", "biodiversity food");
        assertTrue(distinctive > generic, "Sharing rare wording must count for more than sharing the corpus's own vocabulary");
    }

    @Test
    void testFallsBackToPlainJaccardWhenTooFewDocumentsToJudgeRarity() {
        // With two documents every shared word occurs in "all" of them, so rarity is meaningless; shared wording is still
        // required, and is measured unweighted.
        LexicalOverlap overlap = LexicalOverlap.fromDocuments(List.of("alpha sentence here", "alpha thing"));
        assertEquals(0.25, overlap.score("alpha sentence here", "alpha thing"), 1e-9, "One shared word of four distinct words is 0.25");
    }

    @Test
    void testNoSharedWordingScoresZero() {
        LexicalOverlap overlap = LexicalOverlap.fromDocuments(CORPUS);
        assertEquals(0.0, overlap.score("polyculture planting", "entirely different terminology"), 1e-9, "Nothing shared is zero");
    }

    @Test
    void testHandlesEmptyInput() {
        LexicalOverlap overlap = LexicalOverlap.fromDocuments(CORPUS);
        assertEquals(0.0, overlap.score("", "polyculture"), 1e-9, "An empty passage shares nothing");
        assertEquals(0.0, overlap.score("polyculture", null), 1e-9, "A null passage shares nothing");
    }
}
