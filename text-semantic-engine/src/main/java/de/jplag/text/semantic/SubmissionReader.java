package de.jplag.text.semantic;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import de.jplag.options.LanguageOption;
import de.jplag.text.NaturalLanguage;
import de.jplag.text.ParserAdapter;
import de.jplag.text.TextLanguageOptions;
import de.jplag.util.FileUtils;

/**
 * Walks a directory recursively and turns every accepted file into one {@link AnalyzedSubmission} — a bag of normalized
 * terms plus the raw text. Each document is named by its path relative to the root, with the directory separators
 * encoded (e.g. {@code sub/dir/file.txt -> sub__dir__file}), so nested files stay distinct and traceable; a
 * {@link DocumentNamer} can name them from the path's parts instead, which is how a report comes to be titled with the
 * module and assignment rather than an export id.
 * <p>
 * Tokenization and normalization are delegated to the text module's {@link ParserAdapter} with WordNet lemmatization,
 * stop-word removal and synonym canonicalization all enabled. That normalization is fixed rather than configurable
 * because it has to match between indexing and querying for the terms to line up.
 */
public class SubmissionReader {

    private static final Logger logger = LoggerFactory.getLogger(SubmissionReader.class);
    private static final String PDF_EXTENSION = ".pdf";

    /** Separator encoding a document's directories into its name. */
    static final String PATH_SEPARATOR = "__";

    private final ParserAdapter parserAdapter;
    private final List<String> fileExtensions;
    private final DocumentNamer namer;

    /**
     * Creates the reader, naming each document by its path.
     * @param fileExtensions the accepted file extensions, with or without a leading dot; matched case-insensitively.
     */
    public SubmissionReader(List<String> fileExtensions) {
        this(fileExtensions, DocumentNamer.pathDerived());
    }

    /**
     * Creates the reader.
     * @param fileExtensions the accepted file extensions, with or without a leading dot; matched case-insensitively.
     * @param namer names each document from its path; see {@link DocumentNamer}.
     */
    public SubmissionReader(List<String> fileExtensions, DocumentNamer namer) {
        this.parserAdapter = new ParserAdapter(normalizationOptions());
        this.fileExtensions = fileExtensions.stream().map(extension -> extension.toLowerCase(Locale.ROOT))
                .map(extension -> extension.startsWith(".") ? extension : "." + extension).toList();
        this.namer = namer;
    }

    /**
     * @return the text module's extensions plus {@code .pdf}, which the reader extracts text from directly.
     */
    public static List<String> defaultFileExtensions() {
        List<String> extensions = new ArrayList<>(new NaturalLanguage().fileExtensions());
        extensions.add(PDF_EXTENSION);
        return extensions;
    }

    private static TextLanguageOptions normalizationOptions() {
        TextLanguageOptions options = new TextLanguageOptions();
        for (LanguageOption<?> option : options.getOptionsAsList()) {
            boolean enabled = switch (option.getName()) {
                case "lemmatize", "removeStopwords", "expandSynonyms" -> true;
                default -> false;
            };
            @SuppressWarnings("unchecked")
            LanguageOption<Boolean> booleanOption = (LanguageOption<Boolean>) option;
            booleanOption.setValue(enabled);
        }
        return options;
    }

    /**
     * Reads every accepted file below the given root as its own document.
     * @param rootDirectory the directory to walk recursively.
     * @return the analyzed documents, ordered by relative path; documents without any terms are omitted.
     * @throws IOException if the directory cannot be read.
     * @throws ParsingException if a file cannot be parsed.
     */
    public List<AnalyzedSubmission> readDocuments(File rootDirectory) throws IOException, ParsingException {
        if (rootDirectory == null || !rootDirectory.isDirectory()) {
            throw new IOException("Not a directory: " + rootDirectory);
        }
        Path root = rootDirectory.toPath().toAbsolutePath().normalize();
        List<File> files = new ArrayList<>(gatherFiles(rootDirectory));
        files.sort(Comparator.comparing(file -> relativePath(root, file)));

        Set<String> usedNames = new HashSet<>();
        List<AnalyzedSubmission> documents = new ArrayList<>();
        // Text extracted from PDFs is written to this temporary directory, then removed afterwards.
        Path pdfTextDirectory = Files.createTempDirectory("jplag-semantic-pdf");
        try {
            for (File file : files) {
                String pathDerived = stripExtension(relativePath(root, file).replace(File.separator, PATH_SEPARATOR));
                String name = uniqueName(namer.nameOf(file.toPath(), pathDerived), usedNames);
                AnalyzedSubmission document = analyze(name, file, pdfTextDirectory);
                if (document.isEmpty()) {
                    logger.warn("Document '{}' contains no usable terms and is skipped.", name);
                } else {
                    documents.add(document);
                }
            }
        } finally {
            deleteRecursively(pdfTextDirectory);
        }
        return documents;
    }

    private AnalyzedSubmission analyze(String name, File file, Path pdfTextDirectory) throws ParsingException, IOException {
        File textFile = isPdf(file) ? extractPdfToTextFile(file, pdfTextDirectory) : file;
        Map<String, Integer> termFrequencies = new HashMap<>();
        for (Token token : parserAdapter.parse(Set.of(textFile))) {
            if (token.getType() != SharedTokenType.FILE_END) {
                termFrequencies.merge(token.getType().getDescription(), 1, Integer::sum);
            }
        }
        return new AnalyzedSubmission(name, termFrequencies, FileUtils.readFileContent(textFile));
    }

    private static String relativePath(Path root, File file) {
        return root.relativize(file.toPath().toAbsolutePath().normalize()).toString();
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

    private static File extractPdfToTextFile(File pdfFile, Path pdfTextDirectory) throws IOException {
        File textFile = Files.createTempFile(pdfTextDirectory, "pdf-", ".txt").toFile();
        Files.writeString(textFile.toPath(), PdfTextExtractor.extractText(pdfFile));
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
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            return paths.filter(Files::isRegularFile).map(Path::toFile).filter(this::hasAcceptedExtension).collect(Collectors.toSet());
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
