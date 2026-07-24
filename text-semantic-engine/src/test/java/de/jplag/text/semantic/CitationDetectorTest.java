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
    @ValueSource(strings = {"He argued that \"the result is significant\" in his talk.", "The author wrote “an exact copy of the line” here."})
    void testDetectsQuotations(String sentence) {
        assertEquals(AttributionStatus.QUOTED, CitationDetector.detect(sentence).status());
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
