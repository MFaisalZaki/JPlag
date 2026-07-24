package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies the engine emits a {@code .jplag} archive with the structure the JPlag report viewer requires.
 */
class JPlagReportWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode readEntry(ZipFile zip, String entry) throws IOException {
        return MAPPER.readTree(zip.getInputStream(zip.getEntry(entry)));
    }

    @Test
    void testReportArchiveHasRequiredStructure(@TempDir Path temp) throws IOException {
        List<AnalyzedSubmission> submissions = List.of(new AnalyzedSubmission("alice", Map.of("clever", 2, "algorithm", 1)),
                new AnalyzedSubmission("bob", Map.of("clever", 1, "method", 1)));
        List<SubmissionPairSimilarity> results = List.of(new SubmissionPairSimilarity("alice", "bob", 0.73, List.of(new SharedTerm("clever", 0.5))));

        File reportFile = temp.resolve("report.jplag").toFile();
        new JPlagReportWriter().write(submissions, results, reportFile);

        try (ZipFile zip = new ZipFile(reportFile)) {
            // All files the viewer loads on open must be present.
            for (String required : Set.of("runInformation.json", "options.json", "cluster.json", "distribution.json", "topComparisons.json",
                    "submissionMappings.json", "submissionFileIndex.json")) {
                assertTrue(zip.getEntry(required) != null, "Missing required report entry: " + required);
            }

            // Version must be >= 6.2.0 or the viewer rejects the report.
            JsonNode version = readEntry(zip, "runInformation.json").get("version");
            int major = version.get("major").asInt();
            int minor = version.get("minor").asInt();
            assertTrue(major > 6 || (major == 6 && minor >= 2), "Report version must be >= 6.2.0, was " + major + "." + minor);

            // The cosine score must land in the AVG metric (what the viewer sorts/displays by default).
            JsonNode top = readEntry(zip, "topComparisons.json");
            assertEquals(1, top.size());
            assertEquals(0.73, top.get(0).get("similarities").get("AVG").asDouble(), 1e-9);
            assertEquals("alice", top.get(0).get("firstSubmission").asText());

            // The per-comparison file is present, referenced symmetrically, and has an (empty) matches array.
            JsonNode mappings = readEntry(zip, "submissionMappings.json");
            String fileName = mappings.get("submissionIdsToComparisonFileName").get("alice").get("bob").asText();
            assertTrue(zip.getEntry("comparisons/" + fileName) != null, "Comparison file referenced by mappings must exist");
            JsonNode comparison = readEntry(zip, "comparisons/" + fileName);
            assertTrue(comparison.get("matches").isArray() && comparison.get("matches").isEmpty());

            // Distribution has the AVG and MAX histograms of 100 buckets each.
            JsonNode distribution = readEntry(zip, "distribution.json");
            assertEquals(100, distribution.get("AVG").size());
            assertEquals(1, distribution.get("AVG").get(73).asInt(), "The 0.73 pair should fall in bucket 73");

            // options.json must carry every field the viewer's CliOptions schema reads, or the overview/information
            // views throw (e.g. `submissionDirectories.length` on an undefined value).
            JsonNode options = readEntry(zip, "options.json");
            for (String required : Set.of("language", "minimumTokenMatch", "submissionDirectories", "oldSubmissionDirectories",
                    "baseCodeSubmissionDirectory", "subdirectoryName", "fileSuffixes", "exclusionFileName", "similarityMetric", "similarityThreshold",
                    "maximumNumberOfComparisons", "clusteringOptions", "mergingOptions", "normalize", "analyzeComments")) {
                assertTrue(options.has(required), "options.json is missing required field: " + required);
            }
            assertTrue(options.get("submissionDirectories").isArray(), "submissionDirectories must be an array");
            assertTrue(options.get("oldSubmissionDirectories").isArray(), "oldSubmissionDirectories must be an array");
            assertTrue(options.get("fileSuffixes").isArray(), "fileSuffixes must be an array");
            assertTrue(options.get("clusteringOptions").has("enabled"), "clusteringOptions must expose enabled");
            assertTrue(options.get("mergingOptions").has("enabled"), "mergingOptions must expose enabled");
            assertEquals(1, options.get("maximumNumberOfComparisons").asInt(), "maximumNumberOfComparisons should match the pair count");
        }
    }
}
