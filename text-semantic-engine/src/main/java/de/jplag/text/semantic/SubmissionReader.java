package de.jplag.text.semantic;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import de.jplag.util.FileUtils;

/**
 * Reads submissions from a root directory and turns each into an {@link AnalyzedSubmission} (a bag of normalized
 * terms).
 * <p>
 * Two ingestion modes are offered:
 * <ul>
 * <li>{@link #readSubmissions(File)} — JPlag-style: a submission is either a direct sub-directory of the root (all of
 * its matching files combined) or a single matching file directly in the root. Used where a submission may span several
 * files (e.g. authorship verification, where each sub-directory is one author).</li>
 * <li>{@link #readDocuments(File)} — document-per-file: the root is walked <em>recursively</em> and every matching file
 * anywhere below it becomes its own document, named by its path relative to the root (so nested files stay distinct and
 * traceable). Used for corpus indexing and querying, where each file is an independent document.</li>
 * </ul>
 * Tokenization and normalization are delegated to the text module's {@link ParserAdapter}, so the same WordNet-based
 * lemmatization, stop-word removal and synonym canonicalization apply in both modes.
 */
public class SubmissionReader {

    private static final Logger logger = LoggerFactory.getLogger(SubmissionReader.class);
    private static final String PDF_EXTENSION = ".pdf";

    private final ParserAdapter parserAdapter;
    private final List<String> fileExtensions;

    /**
     * A named group of files that together form one submission/document.
     * @param name the submission/document name.
     * @param files the files that make up this submission/document.
     */
    private record NamedFiles(String name, Set<File> files) {
    }

    /**
     * Creates the reader.
     * @param configuration the engine configuration (normalization options and accepted file extensions).
     */
    public SubmissionReader(SemanticEngineConfiguration configuration) {
        this.parserAdapter = new ParserAdapter(configuration.normalizationOptions());
        // Accept extensions case-insensitively and tolerate them being given with or without a leading dot.
        this.fileExtensions = configuration.fileExtensions().stream().map(extension -> extension.toLowerCase(Locale.ROOT))
                .map(extension -> extension.startsWith(".") ? extension : "." + extension).toList();
    }

    /**
     * Reads submissions directly below the given root: each sub-directory (its matching files combined) or matching file is
     * one submission.
     * @param rootDirectory the directory containing the submissions.
     * @return the analyzed submissions, ordered by name; submissions without any terms are omitted.
     * @throws IOException if the directory cannot be read.
     * @throws ParsingException if a file cannot be parsed.
     */
    public List<AnalyzedSubmission> readSubmissions(File rootDirectory) throws IOException, ParsingException {
        File[] children = listRoot(rootDirectory);
        Arrays.sort(children, Comparator.comparing(File::getName));
        List<NamedFiles> groups = new ArrayList<>();
        for (File child : children) {
            Set<File> files = child.isDirectory() ? gatherFiles(child) : (hasAcceptedExtension(child) ? Set.of(child) : Set.of());
            if (files.isEmpty()) {
                continue;
            }
            // A directory submission keeps its directory name; a single-file submission drops the file extension.
            groups.add(new NamedFiles(child.isDirectory() ? child.getName() : stripExtension(child.getName()), files));
        }
        return analyzeAll(groups);
    }

    /**
     * Recursively reads every matching file below the given root as its own document. Each document is named by its path
     * relative to the root, with the directory separators encoded (e.g. {@code sub/dir/file.txt -> sub__dir__file}) so that
     * nested files remain distinct, uniquely named, and traceable back to their source.
     * @param rootDirectory the directory to walk recursively.
     * @return the analyzed documents, ordered by relative path; documents without any terms are omitted.
     * @throws IOException if the directory cannot be read.
     * @throws ParsingException if a file cannot be parsed.
     */
    public List<AnalyzedSubmission> readDocuments(File rootDirectory) throws IOException, ParsingException {
        listRoot(rootDirectory); // validate it is a readable directory
        List<File> files = new ArrayList<>(gatherFiles(rootDirectory));
        Path root = rootDirectory.toPath().toAbsolutePath().normalize();
        files.sort(Comparator.comparing(file -> root.relativize(file.toPath().toAbsolutePath().normalize()).toString()));
        Set<String> usedNames = new HashSet<>();
        List<NamedFiles> groups = new ArrayList<>();
        for (File file : files) {
            groups.add(new NamedFiles(uniqueName(relativeName(root, file), usedNames), Set.of(file)));
        }
        return analyzeAll(groups);
    }

    private static File[] listRoot(File rootDirectory) throws IOException {
        if (rootDirectory == null || !rootDirectory.isDirectory()) {
            throw new IOException("Not a directory: " + rootDirectory);
        }
        File[] children = rootDirectory.listFiles();
        if (children == null) {
            throw new IOException("Cannot list directory: " + rootDirectory);
        }
        return children;
    }

    /** Analyzes each named group into a submission, skipping empty ones. PDFs are extracted to a shared temp directory. */
    private List<AnalyzedSubmission> analyzeAll(List<NamedFiles> groups) throws IOException, ParsingException {
        List<AnalyzedSubmission> submissions = new ArrayList<>();
        // Text extracted from PDF submissions is written to this temporary directory, then removed afterwards.
        Path pdfTextDirectory = Files.createTempDirectory("jplag-semantic-pdf");
        try {
            for (NamedFiles group : groups) {
                AnalyzedSubmission submission = analyze(group.name(), group.files(), pdfTextDirectory);
                if (submission.isEmpty()) {
                    logger.warn("Document '{}' contains no usable terms and is skipped.", group.name());
                } else {
                    submissions.add(submission);
                }
            }
        } finally {
            deleteRecursively(pdfTextDirectory);
        }
        return submissions;
    }

    private AnalyzedSubmission analyze(String name, Set<File> files, Path pdfTextDirectory) throws ParsingException, IOException {
        Set<File> textFiles = new HashSet<>();
        for (File file : files) {
            textFiles.add(isPdf(file) ? extractPdfToTextFile(file, pdfTextDirectory) : file);
        }
        List<Token> tokens = parserAdapter.parse(textFiles);
        Map<String, Integer> termFrequencies = new HashMap<>();
        for (Token token : tokens) {
            if (token.getType() != SharedTokenType.FILE_END) {
                termFrequencies.merge(token.getType().getDescription(), 1, Integer::sum);
            }
        }
        StringBuilder text = new StringBuilder();
        for (File textFile : textFiles) {
            text.append(FileUtils.readFileContent(textFile)).append('\n');
        }
        return new AnalyzedSubmission(name, termFrequencies, text.toString());
    }

    /** The file's path relative to the root, separators encoded as {@code __} and the file extension dropped. */
    private static String relativeName(Path root, File file) {
        Path relative = root.relativize(file.toPath().toAbsolutePath().normalize());
        return stripExtension(relative.toString().replace(File.separator, "__"));
    }

    /** Ensures the name is unique within this batch, appending {@code ~2}, {@code ~3}, … on collision. */
    private static String uniqueName(String base, Set<String> usedNames) {
        String name = base;
        int suffix = 2;
        while (!usedNames.add(name)) {
            name = base + "~" + suffix++;
        }
        return name;
    }

    private File extractPdfToTextFile(File pdfFile, Path pdfTextDirectory) throws IOException {
        String text = PdfTextExtractor.extractText(pdfFile);
        File textFile = Files.createTempFile(pdfTextDirectory, "pdf-", ".txt").toFile();
        Files.writeString(textFile.toPath(), text);
        return textFile;
    }

    private static boolean isPdf(File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(PDF_EXTENSION);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
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
