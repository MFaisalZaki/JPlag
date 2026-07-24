package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jplag.ParsingException;

/**
 * Verifies that the engine ingests PDF submissions directly (extracting their text) instead of requiring an external
 * conversion step.
 */
class PdfIngestionTest {

    private static void writePdf(Path directory, String name, String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            document.save(directory.resolve(name + ".pdf").toFile());
        }
    }

    @Test
    void testPdfSubmissionsAreReadAndCompared(@TempDir Path root) throws IOException, ParsingException {
        writePdf(root, "first", "The quick clever student purchased several large books.");
        writePdf(root, "second", "The quick clever student purchased several large books.");
        writePdf(root, "unrelated", "Volcanoes erupt when molten rock rises through the crust.");

        SemanticEngineConfiguration configuration = SemanticEngineConfiguration.builder().similarityThreshold(0.0).build();
        List<AnalyzedSubmission> submissions = new SubmissionReader(configuration).readSubmissions(root.toFile());
        List<SubmissionPairSimilarity> results = new SemanticComparisonEngine(configuration).compare(submissions);

        assertEquals(3, submissions.size(), "All three PDF submissions should be read");
        SubmissionPairSimilarity top = results.get(0);
        assertTrue(
                (top.firstSubmission().equals("first") && top.secondSubmission().equals("second"))
                        || (top.firstSubmission().equals("second") && top.secondSubmission().equals("first")),
                "The two identical PDFs should be the most similar pair");
        assertTrue(top.similarity() > 0.9, "Identical PDF content should be near-identical, was " + top.similarity());
    }

    @Test
    void testPdfTextExtraction(@TempDir Path root) throws IOException {
        writePdf(root, "sample", "Photosynthesis converts sunlight into glucose.");
        String extracted = PdfTextExtractor.extractText(new File(root.toFile(), "sample.pdf"));
        assertTrue(extracted.contains("Photosynthesis"), "Extracted text should contain the original words");
        assertTrue(extracted.contains("glucose"), "Extracted text should contain the original words");
    }
}
