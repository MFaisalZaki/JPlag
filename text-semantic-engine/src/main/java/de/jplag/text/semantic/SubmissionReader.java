package de.jplag.text.semantic;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jplag.ParsingException;
import de.jplag.SharedTokenType;
import de.jplag.Token;
import de.jplag.text.ParserAdapter;

/**
 * Reads submissions from a root directory and turns each into an {@link AnalyzedSubmission} (a bag of normalized
 * terms).
 * <p>
 * Like JPlag, a submission is either a direct sub-directory of the root (all of its matching text files are combined)
 * or a single matching file directly in the root. Tokenization and normalization are delegated to the text module's
 * {@link ParserAdapter}, so the same WordNet-based lemmatization, stop-word removal and synonym canonicalization apply.
 */
public class SubmissionReader {

    private static final Logger logger = LoggerFactory.getLogger(SubmissionReader.class);

    private final ParserAdapter parserAdapter;
    private final List<String> fileExtensions;

    /**
     * Creates the reader.
     * @param configuration the engine configuration (normalization options and accepted file extensions).
     */
    public SubmissionReader(SemanticEngineConfiguration configuration) {
        this.parserAdapter = new ParserAdapter(configuration.normalizationOptions());
        this.fileExtensions = configuration.fileExtensions().stream().map(extension -> extension.toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * Reads all submissions below the given root directory.
     * @param rootDirectory the directory containing the submissions.
     * @return the analyzed submissions, ordered by name; submissions without any terms are omitted.
     * @throws IOException if the directory cannot be read.
     * @throws ParsingException if a file cannot be parsed.
     */
    public List<AnalyzedSubmission> readSubmissions(File rootDirectory) throws IOException, ParsingException {
        if (rootDirectory == null || !rootDirectory.isDirectory()) {
            throw new IOException("Not a directory: " + rootDirectory);
        }
        List<AnalyzedSubmission> submissions = new ArrayList<>();
        File[] children = rootDirectory.listFiles();
        if (children == null) {
            throw new IOException("Cannot list directory: " + rootDirectory);
        }
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            Set<File> files = child.isDirectory() ? gatherFiles(child) : (hasAcceptedExtension(child) ? Set.of(child) : Set.of());
            if (files.isEmpty()) {
                continue;
            }
            // A directory submission keeps its directory name; a single-file submission drops the file extension.
            String name = child.isDirectory() ? child.getName() : stripExtension(child.getName());
            AnalyzedSubmission submission = analyze(name, files);
            if (submission.isEmpty()) {
                logger.warn("Submission '{}' contains no usable terms and is skipped.", child.getName());
            } else {
                submissions.add(submission);
            }
        }
        return submissions;
    }

    private AnalyzedSubmission analyze(String name, Set<File> files) throws ParsingException {
        List<Token> tokens = parserAdapter.parse(files);
        Map<String, Integer> termFrequencies = new HashMap<>();
        for (Token token : tokens) {
            if (token.getType() != SharedTokenType.FILE_END) {
                termFrequencies.merge(token.getType().getDescription(), 1, Integer::sum);
            }
        }
        return new AnalyzedSubmission(name, termFrequencies);
    }

    private Set<File> gatherFiles(File directory) throws IOException {
        try (Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            return paths.filter(Files::isRegularFile).map(java.nio.file.Path::toFile).filter(this::hasAcceptedExtension).collect(Collectors.toSet());
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static String stripExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private boolean hasAcceptedExtension(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return fileExtensions.stream().anyMatch(name::endsWith);
    }
}
