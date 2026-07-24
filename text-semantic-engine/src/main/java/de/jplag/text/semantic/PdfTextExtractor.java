package de.jplag.text.semantic;

import java.io.File;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Extracts plain text from PDF submissions using Apache PDFBox. The JPlag comparison core and the text language module
 * only read plain-text formats; this lets the semantic engine accept {@code .pdf} files directly.
 */
public final class PdfTextExtractor {

    private PdfTextExtractor() {
        // utility class
    }

    /**
     * Extracts the text content of a PDF file.
     * @param pdfFile the PDF file.
     * @return the extracted text.
     * @throws IOException if the file cannot be read or is not a valid PDF.
     */
    public static String extractText(File pdfFile) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
