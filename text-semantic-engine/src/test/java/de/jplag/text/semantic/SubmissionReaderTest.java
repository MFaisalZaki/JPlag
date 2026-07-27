package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jplag.ParsingException;

/**
 * Tests {@link SubmissionReader}'s recursive, document-per-file ingestion.
 */
class SubmissionReaderTest {

    private static final String TEXT = "The students purchased several large books for their difficult research project.";

    private static SubmissionReader reader() {
        return new SubmissionReader(SubmissionReader.defaultFileExtensions());
    }

    private static Set<String> names(List<AnalyzedSubmission> submissions) {
        return submissions.stream().map(AnalyzedSubmission::name).collect(Collectors.toSet());
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Test
    void readDocumentsWalksNestedDirectoriesOneDocumentPerFile(@TempDir Path root) throws IOException, ParsingException {
        write(root.resolve("top.txt"), TEXT);
        write(root.resolve("web/blog.txt"), TEXT);
        write(root.resolve("web/deep/explainer.md"), TEXT);

        List<AnalyzedSubmission> documents = reader().readDocuments(root.toFile());

        assertEquals(3, documents.size(), "every nested file should be its own document");
        assertEquals(Set.of("top", "web__blog", "web__deep__explainer"), names(documents),
                "documents should be named by their encoded relative path, extension dropped");
    }

    @Test
    void readDocumentsFiltersUnacceptedExtensions(@TempDir Path root) throws IOException, ParsingException {
        write(root.resolve("essay.txt"), TEXT);
        write(root.resolve("logs/run.log"), TEXT);   // not an accepted extension
        write(root.resolve("assets/image.png"), TEXT); // not an accepted extension

        List<AnalyzedSubmission> documents = reader().readDocuments(root.toFile());

        assertEquals(Set.of("essay"), names(documents), "only accepted extensions should be ingested");
    }

    @Test
    void readDocumentsHonoursCustomExtensionList(@TempDir Path root) throws IOException, ParsingException {
        write(root.resolve("keep.log"), TEXT);
        write(root.resolve("drop.txt"), TEXT);
        // "log" is given without a leading dot on purpose: the reader should normalize it to ".log".
        SubmissionReader logsOnly = new SubmissionReader(List.of("log"));

        List<AnalyzedSubmission> documents = logsOnly.readDocuments(root.toFile());

        assertEquals(Set.of("keep"), names(documents), "a dotless extension should still match, and only it");
    }

    @Test
    void readDocumentsKeepsSameBasenamesInDifferentFoldersDistinct(@TempDir Path root) throws IOException, ParsingException {
        write(root.resolve("2023/report.txt"), TEXT);
        write(root.resolve("2024/report.txt"), TEXT);

        List<AnalyzedSubmission> documents = reader().readDocuments(root.toFile());

        assertEquals(2, documents.size(), "identical basenames in different folders must not collide");
        assertEquals(Set.of("2023__report", "2024__report"), names(documents));
    }

}
