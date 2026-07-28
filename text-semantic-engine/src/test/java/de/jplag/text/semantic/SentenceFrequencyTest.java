package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Tests treating sentences that much of a cohort submitted as given material rather than anyone's own writing.
 */
class SentenceFrequencyTest {

    private static final String TEMPLATE = "The purpose of the lab was to create a web map identifying limited vehicle access.";
    private static final Function<String, List<String>> SPLITTER = text -> List.of(text.split("\\|"));

    /** A cohort of {@code size} documents, the first {@code sharing} of which contain the template sentence. */
    private static List<String> cohort(int size, int sharing) {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            texts.add((i < sharing ? TEMPLATE + "|" : "") + "This student wrote their own distinct sentence number " + i + " here.");
        }
        return texts;
    }

    @Test
    void testSentenceSharedByMuchOfTheCohortIsCommon() {
        SentenceFrequency frequency = SentenceFrequency.of(cohort(20, 10), SPLITTER, 0.10);

        assertTrue(frequency.isCommon(TEMPLATE), "A sentence in half the cohort is given material");
    }

    @Test
    void testSentenceUniqueToOneDocumentIsNotCommon() {
        SentenceFrequency frequency = SentenceFrequency.of(cohort(20, 10), SPLITTER, 0.10);

        assertFalse(frequency.isCommon("This student wrote their own distinct sentence number 3 here."), "One student's own prose is not shared");
    }

    @Test
    void testSentenceInTooFewDocumentsIsNotCommon() {
        // 2 of 40 clears the 10% share only if the absolute floor is ignored; two students sharing a sentence is the
        // very thing the check exists to report.
        SentenceFrequency frequency = SentenceFrequency.of(cohort(40, 2), SPLITTER, 0.02);

        assertFalse(frequency.isCommon(TEMPLATE), "A sentence in two documents is a match to investigate, not boilerplate");
    }

    @Test
    void testNearDuplicatesCountAsTheSameSentence() {
        SentenceFrequency frequency = SentenceFrequency.of(cohort(20, 10), SPLITTER, 0.10);

        assertTrue(frequency.isCommon("the purpose of the lab was to create a web map identifying limited vehicle access, per the brief"),
                "Punctuation, case and a trailing clause should not make it a different sentence");
    }

    @Test
    void testFilterSwitchesItselfOffForASmallCohort() {
        SentenceFrequency frequency = SentenceFrequency.of(cohort(5, 5), SPLITTER, 0.10);

        assertFalse(frequency.isEnabled(), "Document frequency over five documents says nothing");
        assertFalse(frequency.isCommon(TEMPLATE));
    }

    @Test
    void testShareOfZeroDisablesTheFilter() {
        SentenceFrequency frequency = SentenceFrequency.of(cohort(20, 20), SPLITTER, 0);

        assertFalse(frequency.isEnabled());
        assertFalse(frequency.isCommon(TEMPLATE));
    }

    @Test
    void testDisabledInstanceReportsNothing() {
        assertFalse(SentenceFrequency.disabled().isCommon(TEMPLATE));
        assertFalse(SentenceFrequency.disabled().isEnabled());
        assertTrue(Collections.emptyList().isEmpty());
    }
}
