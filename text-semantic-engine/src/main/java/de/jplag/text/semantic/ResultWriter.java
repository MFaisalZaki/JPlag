package de.jplag.text.semantic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Writes the engine's results as a pretty-printed JSON file and a CSV file.
 */
public class ResultWriter {

    private static final String JSON_FILE_NAME = "semantic-results.json";
    private static final String CSV_FILE_NAME = "semantic-results.csv";

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Writes both a JSON and a CSV report into the given directory.
     * @param results the pair similarities to write.
     * @param outputDirectory the directory to write into (created if necessary).
     * @throws IOException if writing fails.
     */
    public void write(List<SubmissionPairSimilarity> results, File outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory.toPath());
        writeJson(results, new File(outputDirectory, JSON_FILE_NAME));
        writeCsv(results, new File(outputDirectory, CSV_FILE_NAME));
    }

    private void writeJson(List<SubmissionPairSimilarity> results, File file) throws IOException {
        objectMapper.writeValue(file, results);
    }

    private void writeCsv(List<SubmissionPairSimilarity> results, File file) throws IOException {
        StringBuilder builder = new StringBuilder("first_submission,second_submission,similarity,top_shared_terms\n");
        for (SubmissionPairSimilarity result : results) {
            String terms = result.topSharedTerms().stream().map(SharedTerm::term).collect(Collectors.joining(" "));
            builder.append(escape(result.firstSubmission())).append(',').append(escape(result.secondSubmission())).append(',')
                    .append(String.format(Locale.ROOT, "%.4f", result.similarity())).append(',').append(escape(terms)).append('\n');
        }
        Files.writeString(file.toPath(), builder.toString(), StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
