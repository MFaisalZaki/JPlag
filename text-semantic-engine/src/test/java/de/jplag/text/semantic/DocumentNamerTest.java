package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Tests naming a document from its path, so a report is titled with the academic year, module, assignment and student
 * rather than with the export ids the file name happens to carry.
 */
class DocumentNamerTest {

    /** The layout a term's submissions arrive in: {@code <ayr>/<module>/<coursework>/[warned/]<file>}. */
    private static final String LAYOUT = "(?<ayr>[^/]+)/(?<module>[^/]+)/[^/]+/(?<warned>warned)?/?"
            + "(?<student>[0-9]+)-(?<assignment>.+?)-(?<submission>[0-9]+)\\.[^./]+$";
    private static final String TEMPLATE = "{ayr}-{module}-{assignment}-{student}-{warned}";

    private static String name(String path) {
        return new DocumentNamer(LAYOUT, TEMPLATE).nameOf(Path.of(path), "fallback");
    }

    @Test
    void testNameCarriesTheFactsAReaderNeedsInsteadOfExportIds() {
        assertEquals("2025_6-AH1001-MTP-240026012", name("/corpus/2025_6/AH1001/865937/240026012-MTP-4973291.pdf"),
                "The name should read as academic year, module, assignment, student");
    }

    @Test
    void testDirectoriesAboveTheCorpusDoNotChangeTheName() {
        // The pattern is matched against the whole path, so anything above the corpus has to be ignored - a run on a
        // laptop and a run on the cluster have to produce the same index keys.
        assertEquals(name("/corpus/2025_6/AH1001/865937/240026012-MTP-4973291.pdf"),
                name("/mnt/scratch/user/archive/2025_6/AH1001/865937/240026012-MTP-4973291.pdf"),
                "The name must not depend on where the corpus sits");
    }

    @Test
    void testAnAssignmentNameMayContainSeparators() {
        assertEquals("2025_6-SD2005-Paired_Report-240024348", name("/corpus/2025_6/SD2005/883465/240024348-Paired_Report-5022919.pdf"));
    }

    @Test
    void testAnOptionalPartAppearsOnlyWhereItApplies() {
        // 'warned' is a sub-directory only some submissions sit in. Where it is missing it contributes nothing, and the
        // separator that would have joined it is tidied away rather than left dangling.
        assertEquals("2025_6-AH1001-MTP-240026012-warned", name("/corpus/2025_6/AH1001/865937/warned/240026012-MTP-4973291.pdf"));
        assertEquals("2025_6-AH1001-MTP-240026012", name("/corpus/2025_6/AH1001/865937/240026012-MTP-4973291.pdf"));
    }

    @Test
    void testGroupsCanBeReferredToByNumber() {
        DocumentNamer namer = new DocumentNamer("([A-Z]{2}[0-9]{4})/[0-9]+/([0-9]+)-", "{1}-{2}");
        assertEquals("AH1001-240026012", namer.nameOf(Path.of("/corpus/2025_6/AH1001/865937/240026012-MTP-4973291.pdf"), "fallback"));
    }

    @Test
    void testUnmatchedPathsKeepTheirDefaultName() {
        // A corpus is rarely organized in one way throughout. A file the pattern does not cover still has to be ingested,
        // under the name it would have had anyway, rather than failing the run or colliding with every other stray file.
        assertEquals("fallback", name("/corpus/loose-notes/reading.pdf"));
    }

    @Test
    void testNamesAreSafeToUseAsFileNames() {
        // The name is also the report's file name, so a path separator or a space in a captured group cannot survive.
        DocumentNamer namer = new DocumentNamer("archive/(?<rest>.+)\\.pdf$", "{rest}");
        assertEquals("term_one_essays_first_draft", namer.nameOf(Path.of("/corpus/archive/term one/essays/first draft.pdf"), "f"));
    }

    @Test
    void testNoNamingConfiguredKeepsThePathDerivedName() {
        assertEquals("web__blog", DocumentNamer.pathDerived().nameOf(Path.of("/corpus/web/blog.txt"), "web__blog"));
    }

    @Test
    void testAHalfConfiguredNamingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentNamer("(?<module>[A-Z]{2}[0-9]{4})", null),
                "A pattern without a template cannot name anything");
        assertThrows(IllegalArgumentException.class, () -> new DocumentNamer("", "{module}"), "A template without a pattern has no groups to fill");
    }

    @Test
    void testATemplateReferringToAGroupThePatternLacksIsRejected() {
        // Otherwise every document in the corpus quietly gets the same name, and only the '~2' suffixes tell you.
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new DocumentNamer("(?<module>[A-Z]{2}[0-9]{4})", "{module}-{studnet}"));
        assertTrue(exception.getMessage().contains("studnet"), "The message should name the group that is missing");
    }
}
