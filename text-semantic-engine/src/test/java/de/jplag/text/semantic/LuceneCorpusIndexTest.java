package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the Lucene corpus index (BM25 lexical, vector semantic, and RRF ensemble retrieval) with a deterministic stub
 * embedder, so no neural model is loaded.
 */
class LuceneCorpusIndexTest {

    /** One-hot vectors keyed by a marker word, so "semantically" related texts get identical vectors. */
    private static final DocumentEmbedder STUB_EMBEDDER = text -> {
        if (text.contains("alpha")) {
            return new float[] {1, 0, 0};
        }
        if (text.contains("beta")) {
            return new float[] {0, 1, 0};
        }
        return new float[] {0, 0, 1};
    };

    private LuceneCorpusIndex index;

    private static AnalyzedSubmission document(String name, String marker, Map<String, Integer> terms) {
        return new AnalyzedSubmission(name, terms, marker + " document text");
    }

    @BeforeEach
    void setUp(@TempDir Path indexDir) throws IOException {
        index = new LuceneCorpusIndex(indexDir, STUB_EMBEDDER);
        index.index(List.of(document("alpha-doc", "alpha", Map.of("alpha", 3, "common", 1)),
                document("beta-doc", "beta", Map.of("beta", 3, "common", 1)), document("gamma-doc", "gamma", Map.of("gamma", 3))),
                document -> Set.of());
    }

    @Test
    void testSizeReflectsIndexedDocuments() throws IOException {
        assertEquals(3, index.size());
    }

    @Test
    void testStoresAndRetrievesAuthor() throws IOException {
        index.index(List.of(document("authored-doc", "alpha", Map.of("alpha", 2))), document -> Set.of("alice"));
        List<ArchivedDocument> retrieved = index.documents(List.of("authored-doc"));
        assertEquals(1, retrieved.size());
        assertEquals(Set.of("alice"), retrieved.get(0).authors(), "The stored author should be retrieved");
    }

    @Test
    void testStoresEveryAuthorOfAPairedSubmission() throws IOException {
        index.index(List.of(document("paired-doc", "alpha", Map.of("alpha", 2))), document -> Set.of("alice", "bob"));
        assertEquals(Set.of("alice", "bob"), index.documents(List.of("paired-doc")).get(0).authors());
    }

    @Test
    void testExcludesAPartnersCopyOfAPairedSubmission() throws IOException {
        // Each member of a pair submits the same document under their own id, so the partner's copy is a perfect match
        // that is not plagiarism. Sharing one author is enough to exclude it.
        index.index(List.of(document("bob-copy", "alpha", Map.of("alpha", 3, "common", 1))), document -> Set.of("bob", "alice"));
        AnalyzedSubmission query = document("alice-copy", "alpha", Map.of("alpha", 3, "common", 1));

        List<CorpusMatch> matches = index.query(query, Backend.ENSEMBLE, 5, Set.of("alice", "bob"));

        assertTrue(matches.stream().noneMatch(match -> match.documentId().equals("bob-copy")), "A partner's copy should not be reported as a source");
    }

    @Test
    void testLexicalRetrievalRanksTermOverlapFirst() throws IOException {
        AnalyzedSubmission query = document("query", "alpha", Map.of("alpha", 2, "common", 1));
        List<CorpusMatch> matches = index.query(query, Backend.TFIDF, 3, Set.of());
        assertEquals("alpha-doc", matches.get(0).documentId(), "The document sharing the rare term should rank first");
    }

    @Test
    void testSemanticRetrievalRanksNearestVectorFirst() throws IOException {
        AnalyzedSubmission query = document("query", "beta", Map.of("unrelated", 1));
        List<CorpusMatch> matches = index.query(query, Backend.SBERT, 3, Set.of());
        assertEquals("beta-doc", matches.get(0).documentId(), "The nearest embedding should rank first");
    }

    @Test
    void testEnsembleFusesBothSignals() throws IOException {
        AnalyzedSubmission query = document("query", "alpha", Map.of("alpha", 2, "common", 1));
        List<CorpusMatch> matches = index.query(query, Backend.ENSEMBLE, 3, Set.of());
        assertEquals("alpha-doc", matches.get(0).documentId());
    }

    @Test
    void testDocumentIsNotMatchedAgainstItself() throws IOException {
        index.index(List.of(document("query", "alpha", Map.of("alpha", 3, "common", 1))), document -> Set.of());
        AnalyzedSubmission query = document("query", "alpha", Map.of("alpha", 3, "common", 1));
        List<CorpusMatch> matches = index.query(query, Backend.ENSEMBLE, 5, Set.of());
        assertFalse(matches.stream().anyMatch(match -> match.documentId().equals("query")), "A document must not match itself");
        assertTrue(matches.stream().anyMatch(match -> match.documentId().equals("alpha-doc")));
    }

    @Test
    void testExcludedAuthorsDocumentsAreNotMatched() throws IOException {
        // The author's earlier submission is a near-duplicate stored under a different id, so id exclusion cannot catch it.
        index.index(List.of(document("alice-draft", "alpha", Map.of("alpha", 3, "common", 1))), document -> Set.of("alice"));
        AnalyzedSubmission query = document("alice-final", "alpha", Map.of("alpha", 3, "common", 1));

        List<CorpusMatch> unfiltered = index.query(query, Backend.ENSEMBLE, 5, Set.of());
        assertTrue(unfiltered.stream().anyMatch(match -> match.documentId().equals("alice-draft")),
                "Without author exclusion the resubmission is a regular match");

        for (Backend backend : Backend.values()) {
            List<CorpusMatch> matches = index.query(query, backend, 5, Set.of("alice"));
            assertFalse(matches.stream().anyMatch(match -> match.documentId().equals("alice-draft")),
                    "A document must not match its own author's other work (" + backend + ")");
            assertTrue(matches.stream().anyMatch(match -> match.documentId().equals("alpha-doc")),
                    "Other authors' documents must still match (" + backend + ")");
        }
    }
}
