package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.jplag.text.semantic.DocumentSections.Section;

/**
 * Tests splitting a submission into its cover sheet, its body, and its reference list.
 */
class DocumentSectionsTest {

    /** A cover sheet, some body prose, then a bibliography — the shape of a typical submission. */
    private static final List<String> SUBMISSION = List.of("Module Code: SD2005", "Student ID Number: 240009742",
            "I certify that this work is my own and that the word count is 1200.", "The purpose of this report is to examine flood exposure.",
            "Census tracts were joined to the demographic layer and symbolised by quintile.",
            "The results show that vehicle access is the strongest single predictor.", "References",
            "Boas, H. (2025). Are organ donations expressions of altruism or solidarity?", "Social Theory & Health, 23(1).");

    @Test
    void testSplitsCoverSheetBodyAndReferences() {
        List<Section> sections = DocumentSections.of(SUBMISSION);

        assertEquals(List.of(Section.FRONT_MATTER, Section.FRONT_MATTER, Section.FRONT_MATTER, Section.BODY, Section.BODY, Section.BODY,
                Section.REFERENCES, Section.REFERENCES, Section.REFERENCES), sections);
    }

    @Test
    void testEverythingAfterTheHeadingIsReferences() {
        // The entries themselves look nothing like citations once sentence splitting has shredded them, which is the
        // whole reason the section is found by its heading rather than by the shape of its lines.
        List<Section> sections = DocumentSections.of(SUBMISSION);

        assertEquals(Section.REFERENCES, sections.get(sections.size() - 1));
    }

    @Test
    void testProseWithoutACoverSheetOrReferencesIsAllBody() {
        List<String> essay = List.of("Solidarity is often described as a moral economy rather than a transaction.",
                "That framing makes the donor's motive secondary to the relationship it sustains.",
                "The following section tests that claim against the transplantation literature.",
                "Neither reading survives contact with the Dutch case.");

        assertEquals(List.of(Section.BODY, Section.BODY, Section.BODY, Section.BODY), DocumentSections.of(essay));
    }

    @Test
    void testASingleIncidentalMarkerDoesNotMakeAPrefixFrontMatter() {
        // "word count" alone, in prose, is not a cover sheet: two markers are required.
        List<String> essay = List.of("The word count of a submission is a poor proxy for its quality.",
                "Longer essays are not better essays, as every marker knows.", "This report argues the case in three parts.",
                "The first concerns measurement.", "The second concerns incentives.", "The third concerns policy.");

        assertEquals(List.of(Section.BODY, Section.BODY, Section.BODY, Section.BODY, Section.BODY, Section.BODY), DocumentSections.of(essay));
    }

    @Test
    void testUnannouncedReferenceBlockIsStillReferences() {
        // A worksheet's bibliography is the answer to a numbered question, so no heading announces it. The entries
        // cluster, which is enough to find the block; the article titles between them come along with it.
        List<String> worksheet = List.of("Give a full reference for each of the five papers you read this semester.",
                "Boas, H. (2025). Are organ donations expressions of altruism or solidarity?", "Social Theory & Health, 23(1).",
                "https://doi.org/10.1057/s41285-025-00221-0", "Furlong, M. (2025). Encouraging the Local Relationships That Build Solidarity.",
                "Australian and New Zealand Journal of Family Therapy, 46(3).", "Now write an abstract of no more than 250 words.");
        List<Section> sections = DocumentSections.of(worksheet);

        assertEquals(Section.BODY, sections.get(0), "The question itself is the student's to answer");
        assertEquals(List.of(Section.REFERENCES, Section.REFERENCES, Section.REFERENCES, Section.REFERENCES, Section.REFERENCES),
                sections.subList(1, 6), "The whole block of entries, titles included, is a reference list");
    }

    @Test
    void testCitedProseIsNotMistakenForAReferenceBlock() {
        // Three consecutive sentences each carrying a parenthetical citation are a well-referenced paragraph, not a
        // bibliography, and must stay checkable.
        List<String> cited = List.of("Solidarity is best read as a moral economy (Boas, 2025).",
                "That reading survives the Dutch case only in part (Mahmoud & Hunklinger, 2025).",
                "Others argue the opposite from the same evidence (Furlong, 2025).", "The following section sets out the disagreement.");

        assertEquals(List.of(Section.BODY, Section.BODY, Section.BODY, Section.BODY), DocumentSections.of(cited));
    }

    @Test
    void testTableRowsAndDataAreNotProse() {
        assertTrue(DocumentSections.isTabular("STEP Fraction number A488 GFP concentration 0.021 3.96 x 10-6 118.9 2"));
        assertTrue(DocumentSections.isTabular("Renaissance Quarterly, vol. 47, no. 3, 1994, pp. 485-532."));
        assertTrue(DocumentSections.isTabular("STEM students= 116, non-STEM majors= 64"));
    }

    @Test
    void testProseWithSomeFiguresIsStillProse() {
        assertFalse(DocumentSections
                .isTabular("In 1914 some 1.2 million men enlisted, and by the armistice of 1918 the figure had reached five million."));
        assertFalse(DocumentSections.isTabular("The results show that vehicle access is the strongest single predictor of evacuation delay."));
    }

    @Test
    void testAContentsEntryIsNotTheReferenceList() {
        // "References ... 14" in a table of contents sits at the front, so it must not swallow the whole document.
        List<String> withContents = List.of("Contents", "Introduction 1", "References 14", "The introduction sets out the research question.",
                "The method follows Ostrom's institutional analysis framework.", "The findings are presented by region.",
                "The discussion returns to the framework.", "A conclusion follows.");

        assertEquals(Section.BODY, DocumentSections.of(withContents).get(2));
    }
}
