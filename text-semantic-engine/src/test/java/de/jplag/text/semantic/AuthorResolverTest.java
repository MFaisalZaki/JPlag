package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests resolving per-document authors from document names via a file-name regex (with fallback to a fixed author) and
 * co-authors from the document's cover sheet.
 */
class AuthorResolverTest {

    private static final String STUDENT_ID_PATTERN = "^([0-9]+)-";

    @Test
    void testExtractsCaptureGroupFromFileName() {
        AuthorResolver resolver = new AuthorResolver("", STUDENT_ID_PATTERN, "");
        assertEquals(Set.of("240008189"), resolver.authorsOf("240008189-Op-Ed-4959827", ""));
    }

    @Test
    void testAppliesPatternToSegmentAfterEncodedDirectories() {
        AuthorResolver resolver = new AuthorResolver("", STUDENT_ID_PATTERN, "");
        assertEquals(Set.of("230018551"), resolver.authorsOf("883469__warned__230018551-Compulsory_Q1-5085769", ""));
    }

    @Test
    void testResubmissionsResolveToTheSameAuthor() {
        AuthorResolver resolver = new AuthorResolver("", STUDENT_ID_PATTERN, "");
        assertEquals(resolver.authorsOf("883461__240008189-Op-Ed-4957302", ""), resolver.authorsOf("883461__240008189-Op-Ed-4959827", ""));
    }

    @Test
    void testFindsTheStudentInANameBuiltFromThePath() {
        // Documents named '<ayr>-<module>-<assignment>-<student>' by DocumentNamer no longer start with the student id, so
        // the batch scripts identify it as the one long run of digits instead. Getting this wrong is silent: authors come
        // out empty, same-author exclusion stops working, and every resubmission is reported as plagiarism.
        AuthorResolver resolver = new AuthorResolver("", "([0-9]{8,})", "");

        assertEquals(Set.of("240026012"), resolver.authorsOf("2025_6-AH1001-MTP-240026012", ""), "neither the year nor the module code is an id");
        assertEquals(Set.of("250009956"), resolver.authorsOf("2025_6-AH1001-MTP-250009956-warned", ""));
        assertEquals(Set.of("240026012"), resolver.authorsOf("240026012-MTP-4973291", ""), "and the older file-name-based naming still resolves");
    }

    @Test
    void testUsesWholeMatchWhenPatternHasNoGroup() {
        AuthorResolver resolver = new AuthorResolver("", "^[0-9]+", "");
        assertEquals(Set.of("240008189"), resolver.authorsOf("240008189-Op-Ed-4959827", ""));
    }

    @Test
    void testFallsBackToFixedAuthorWhenPatternDoesNotMatch() {
        AuthorResolver resolver = new AuthorResolver("alice", STUDENT_ID_PATTERN, "");
        assertEquals(Set.of("alice"), resolver.authorsOf("essay-without-id", ""));
    }

    @Test
    void testFixedAuthorAloneAppliesToEveryDocument() {
        AuthorResolver resolver = new AuthorResolver("alice", "", "");
        assertEquals(Set.of("alice"), resolver.authorsOf("240008189-Op-Ed-4959827", ""));
    }

    @Test
    void testCoauthorPatternPicksUpEveryIdOnTheCoverSheet() {
        AuthorResolver resolver = new AuthorResolver("", STUDENT_ID_PATTERN, "\\b2[0-9]{8}\\b");
        String coverSheet = "STUDENT ID No: 240009742 , 240022702 MODULE CODE: SD2005 Paired Data Report";

        assertEquals(Set.of("240009742", "240022702"), resolver.authorsOf("240009742-Paired_Report-5021623", coverSheet));
    }

    @Test
    void testPartnersOfAPairShareAnAuthor() {
        AuthorResolver resolver = new AuthorResolver("", STUDENT_ID_PATTERN, "\\b2[0-9]{8}\\b");
        String coverSheet = "STUDENT ID No: 240009742 240022702 MODULE CODE: SD2005";
        Set<String> mine = resolver.authorsOf("240009742-Paired_Report-5021623", coverSheet);
        Set<String> partners = resolver.authorsOf("240022702-Paired_Report-5021622", coverSheet);

        assertTrue(mine.stream().anyMatch(partners::contains), "Both copies of a paired submission should share an author");
    }

    @Test
    void testUnconfiguredResolverYieldsUnknownAuthor() {
        AuthorResolver resolver = new AuthorResolver("", "", "");
        assertEquals(Set.of(), resolver.authorsOf("240008189-Op-Ed-4959827", ""));
    }
}
