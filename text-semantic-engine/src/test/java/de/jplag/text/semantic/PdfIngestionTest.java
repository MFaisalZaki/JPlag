package de.jplag.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jplag.ParsingException;

/**
 * Verifies that the engine ingests PDF documents directly (extracting their text) instead of requiring an external
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
    void testPdfDocumentsAreReadWithTheirTextAndTerms(@TempDir Path root) throws IOException, ParsingException {
        writePdf(root, "first", "The quick clever student purchased several large books.");
        writePdf(root, "second", "Volcanoes erupt when molten rock rises through the crust.");

        List<AnalyzedSubmission> documents = new SubmissionReader(SubmissionReader.defaultFileExtensions()).readDocuments(root.toFile());

        Set<String> names = documents.stream().map(AnalyzedSubmission::name).collect(Collectors.toSet());
        assertEquals(Set.of("first", "second"), names, "Both PDFs should be ingested as documents");
        AnalyzedSubmission first = documents.stream().filter(document -> document.name().equals("first")).findFirst().orElseThrow();
        assertTrue(first.text().contains("student"), "The document text should be the extracted PDF text");
        assertTrue(first.termFrequencies().containsKey("student"), "The extracted text should be tokenized into terms");
    }

    @Test
    void testPdfTextExtraction(@TempDir Path root) throws IOException {
        writePdf(root, "sample", "Photosynthesis converts sunlight into glucose.");
        String extracted = PdfTextExtractor.extractText(new File(root.toFile(), "sample.pdf"));
        assertTrue(extracted.contains("Photosynthesis"), "Extracted text should contain the original words");
        assertTrue(extracted.contains("glucose"), "Extracted text should contain the original words");
    }
}
