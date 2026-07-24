package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CitationDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {"As shown by others (Smith, 2020).", "This holds (Smith et al., 2019) in general.", "It was known (2018).",
            "See the reference [3] for details.", "Combining approaches [3, 4] works.", "Range of works [3-5] agree.",
            "More at http://example.com/paper today.", "Available at www.example.org now.", "Published as 10.1234/journal.2020.5 recently."})
    void testDetectsCitations(String sentence) {
        assertEquals(AttributionStatus.CITED, CitationDetector.detect(sentence).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"An exact copy of the borrowed sentence is reproduced here word for word.\"",
            "“The entire source line is reproduced verbatim within these quotation marks.”"})
    void testDetectsQuotations(String sentence) {
        assertEquals(AttributionStatus.QUOTED, CitationDetector.detect(sentence).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"He said the word \"briefly\" and then moved on to a completely new topic.",
            "\"I heard,\" said Emily, and then she quietly left the crowded room."})
    void testIgnoresIncidentalOrDialogueQuotes(String sentence) {
        assertEquals(AttributionStatus.UNATTRIBUTED, CitationDetector.detect(sentence).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"This is a plain sentence with no acknowledgement.", "The algorithm sorts the array in place.",
            "We observed the effect in year 2020 during testing."})
    void testDetectsUnattributed(String sentence) {
        assertEquals(AttributionStatus.UNATTRIBUTED, CitationDetector.detect(sentence).status());
    }

    @Test
    void testCitationEvidenceIsCaptured() {
        assertEquals("(Smith, 2020)", CitationDetector.detect("A claim (Smith, 2020).").evidence());
    }
}
