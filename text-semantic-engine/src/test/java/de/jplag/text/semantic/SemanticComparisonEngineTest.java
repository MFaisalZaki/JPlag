package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jplag.ParsingException;

/**
 * End-to-end tests proving the engine detects paraphrasing that reorders and rewrites content, which the token-based
 * Greedy String Tiling core cannot.
 */
class SemanticComparisonEngineTest {

    // Reference passage.
    private static final String ORIGINAL = "The students purchased big books to start their difficult project.";
    // Same meaning, but reordered, with synonyms (purchased->bought, big->large, start->begin, difficult->hard) and a
    // changed inflection (books->book, students->student). Greedy String Tiling would find almost no contiguous match.
    private static final String PARAPHRASE = "To begin the hard assignment, the student bought a large book.";
    // Unrelated content sharing no vocabulary.
    private static final String UNRELATED = "Volcanoes erupt when molten rock rises through the planet's thin crust.";

    private List<SubmissionPairSimilarity> run(Path root, boolean normalize) throws IOException, ParsingException {
        SemanticEngineConfiguration configuration = SemanticEngineConfiguration.builder().similarityThreshold(0.0).lemmatize(normalize)
                .removeStopwords(normalize).expandSynonyms(normalize).build();
        List<AnalyzedSubmission> submissions = new SubmissionReader(configuration).readSubmissions(root.toFile());
        return new SemanticComparisonEngine(configuration).compare(submissions);
    }

    private static void writeSubmission(Path root, String name, String content) throws IOException {
        Files.writeString(root.resolve(name + ".txt"), content);
    }

    private static SubmissionPairSimilarity findPair(List<SubmissionPairSimilarity> results, String first, String second) {
        return results.stream().filter(result -> (result.firstSubmission().equals(first) && result.secondSubmission().equals(second))
                || (result.firstSubmission().equals(second) && result.secondSubmission().equals(first))).findFirst().orElse(null);
    }

    @Test
    void testParaphraseIsRankedAboveUnrelated(@TempDir Path root) throws IOException, ParsingException {
        writeSubmission(root, "original", ORIGINAL);
        writeSubmission(root, "paraphrase", PARAPHRASE);
        writeSubmission(root, "unrelated", UNRELATED);

        List<SubmissionPairSimilarity> results = run(root, true);

        SubmissionPairSimilarity paraphrasePair = findPair(results, "original", "paraphrase");
        assertNotNull(paraphrasePair, "The paraphrase pair should be reported");
        assertEquals("original", results.get(0).firstSubmission());
        assertEquals("paraphrase", results.get(0).secondSubmission());
        assertTrue(paraphrasePair.similarity() > 0.3, "Reordered synonym paraphrase should be clearly similar, was " + paraphrasePair.similarity());

        SubmissionPairSimilarity unrelatedPair = findPair(results, "original", "unrelated");
        double unrelatedSimilarity = unrelatedPair == null ? 0.0 : unrelatedPair.similarity();
        assertTrue(paraphrasePair.similarity() > unrelatedSimilarity, "Paraphrase must score higher than unrelated text");
    }

    @Test
    void testNormalizationImprovesParaphraseDetection(@TempDir Path root) throws IOException, ParsingException {
        writeSubmission(root, "original", ORIGINAL);
        writeSubmission(root, "paraphrase", PARAPHRASE);

        double withNormalization = findPair(run(root, true), "original", "paraphrase").similarity();
        double withoutNormalization = findPair(run(root, false), "original", "paraphrase").similarity();

        assertTrue(withNormalization > withoutNormalization,
                "WordNet normalization should raise the paraphrase similarity (" + withNormalization + " vs " + withoutNormalization + ")");
    }

    @Test
    void testTopSharedTermsExplainTheMatch(@TempDir Path root) throws IOException, ParsingException {
        writeSubmission(root, "original", ORIGINAL);
        writeSubmission(root, "paraphrase", PARAPHRASE);

        SubmissionPairSimilarity pair = findPair(run(root, true), "original", "paraphrase");
        assertTrue(pair.topSharedTerms().stream().anyMatch(term -> term.contribution() > 0.0), "Shared terms should be reported");
        // Contributions must be sorted in descending order.
        List<SharedTerm> terms = pair.topSharedTerms();
        for (int i = 1; i < terms.size(); i++) {
            assertTrue(terms.get(i - 1).contribution() >= terms.get(i).contribution(), "Shared terms must be sorted by contribution");
        }
    }
}
