package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests recognition of assignment cover sheets and academic-integrity declarations, including that ordinary prose using
 * the same words is not mistaken for them.
 */
class BoilerplateDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {"STUDENT ID No:  240015513", "Student ID: 240015513 Module Code: SD2005 Word Count: 788",
            "MODULE TITLE:  From Sustainable Development to Human Security", "WORD COUNT: 788", "DEADLINE DATE: Thursday 9th October 2025",
            "DEGREE PROGRAMME:  Geography (MA)", "ASSIGNMENT:  Op-Ed", "Matriculation Number: 240015513",
            "SCHOOL OF GEOGRAPHY AND SUSTAINABLE DEVELOPMENT"})
    void testRecognisesCoverSheetFields(String passage) {
        assertTrue(BoilerplateDetector.isBoilerplate(passage), "Should be recognised as cover-sheet front matter: " + passage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"I have read the University's Statement on Good Academic Practice; that the following work is my own work.",
            "In submitting this assignment, I hereby confirm that:",
            "significant academic debts and borrowings have been properly acknowledged and referenced.",
            "I hereby declare that this is my own work."})
    void testRecognisesIntegrityDeclarations(String passage) {
        assertTrue(BoilerplateDetector.isBoilerplate(passage), "Should be recognised as an integrity declaration: " + passage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Biodiversity is the variety of plants and animals that sustain life on earth.",
            "The module title suggests a focus on human security, and the reading list bears that out.",
            "Agriculture drives about 90% of global deforestation, stripping away ecosystems that regulate climate.",
            "Target 10: Enhance Biodiversity and Sustainability in Agriculture, Aquaculture, Fisheries and Forestry.",
            "Students of the School of Geography have long argued that food security is a political question, not merely a technical one."})
    void testLeavesGenuineProseAlone(String passage) {
        assertFalse(BoilerplateDetector.isBoilerplate(passage), "Genuine prose must not be treated as front matter: " + passage);
    }

    @Test
    void testLongPassageNeedsMoreThanOneAdministrativeLabel() {
        String prose = "The assignment: to explain, in under a thousand words and without recourse to jargon, why "
                + "biodiversity loss and food insecurity are the same problem viewed from two different angles.";
        assertFalse(BoilerplateDetector.isBoilerplate(prose), "A single label inside a long sentence is prose, not a cover sheet");
    }

    @Test
    void testHandlesBlankInput() {
        assertFalse(BoilerplateDetector.isBoilerplate(null), "Null is not boilerplate");
        assertFalse(BoilerplateDetector.isBoilerplate("   "), "Blank text is not boilerplate");
    }
}
