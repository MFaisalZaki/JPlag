package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests resolving per-document authors from document names via a file-name regex, with fallback to a fixed author.
 */
class AuthorResolverTest {

    private static final String STUDENT_ID_PATTERN = "^([0-9]+)-";

    @Test
    void testExtractsCaptureGroupFromFileName() {
        AuthorResolver resolver = new AuthorResolver("", STUDENT_ID_PATTERN);
        assertEquals("240008189", resolver.authorOf("240008189-Op-Ed-4959827"));
    }

    @Test
    void testAppliesPatternToSegmentAfterEncodedDirectories() {
        AuthorResolver resolver = new AuthorResolver("", STUDENT_ID_PATTERN);
        assertEquals("230018551", resolver.authorOf("883469__warned__230018551-Compulsory_Q1-5085769"));
    }

    @Test
    void testResubmissionsResolveToTheSameAuthor() {
        AuthorResolver resolver = new AuthorResolver("", STUDENT_ID_PATTERN);
        assertEquals(resolver.authorOf("883461__240008189-Op-Ed-4957302"), resolver.authorOf("883461__240008189-Op-Ed-4959827"));
    }

    @Test
    void testUsesWholeMatchWhenPatternHasNoGroup() {
        AuthorResolver resolver = new AuthorResolver("", "^[0-9]+");
        assertEquals("240008189", resolver.authorOf("240008189-Op-Ed-4959827"));
    }

    @Test
    void testFallsBackToFixedAuthorWhenPatternDoesNotMatch() {
        AuthorResolver resolver = new AuthorResolver("alice", STUDENT_ID_PATTERN);
        assertEquals("alice", resolver.authorOf("essay-without-id"));
    }

    @Test
    void testFixedAuthorAloneAppliesToEveryDocument() {
        AuthorResolver resolver = new AuthorResolver("alice", "");
        assertEquals("alice", resolver.authorOf("240008189-Op-Ed-4959827"));
    }

    @Test
    void testUnconfiguredResolverYieldsUnknownAuthor() {
        AuthorResolver resolver = new AuthorResolver("", "");
        assertEquals("", resolver.authorOf("240008189-Op-Ed-4959827"));
    }
}
