package de.jplag.text.semantic;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts plain text from PDF submissions using Apache PDFBox. The JPlag comparison core and the text language module
 * only read plain-text formats; this lets the semantic engine accept {@code .pdf} files directly.
 * <p>
 * Extraction is bounded in pages and in time. A submission is occasionally not an essay but a poster or a scan — tens
 * of megabytes of images on one or two pages — on which PDFBox can spend hours parsing content streams for text that is
 * mostly not there. A run covers a whole cohort, so one such file must not be able to stall it: extraction gives up and
 * the document is skipped rather than taking the run with it.
 */
public final class PdfTextExtractor {

    private static final Logger logger = LoggerFactory.getLogger(PdfTextExtractor.class);

    /** Pages read from a submission; beyond this it is not the prose document this engine checks. */
    private static final int MAXIMUM_PAGES = 200;
    /** Time a single PDF may take before extraction is abandoned. */
    private static final long TIMEOUT_SECONDS = 120;

    private PdfTextExtractor() {
        // utility class
    }

    /**
     * Extracts the text content of a PDF file, up to the page and time limits.
     * @param pdfFile the PDF file.
     * @return the extracted text, or the empty string if extraction timed out.
     * @throws IOException if the file cannot be read or is not a valid PDF.
     */
    public static String extractText(File pdfFile) throws IOException {
        // PDFBox does not check for interruption while parsing a content stream, so the work goes on a daemon thread
        // that the JVM can abandon at exit; the timeout bounds the wait, not the doomed thread itself.
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pdf-text-extraction");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<String> extraction = executor.submit(extract(pdfFile));
            return extraction.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            logger.warn("Extracting text from '{}' exceeded {} s and was abandoned; the document is skipped.", pdfFile, TIMEOUT_SECONDS);
            return "";
        } catch (ExecutionException exception) {
            throw exception.getCause() instanceof IOException cause ? cause : new IOException("Could not read PDF: " + pdfFile, exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading PDF: " + pdfFile, exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Callable<String> extract(File pdfFile) {
        return () -> {
            try (PDDocument document = Loader.loadPDF(pdfFile)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setEndPage(MAXIMUM_PAGES);
                if (document.getNumberOfPages() > MAXIMUM_PAGES) {
                    logger.warn("'{}' has {} pages; only the first {} are checked.", pdfFile, document.getNumberOfPages(), MAXIMUM_PAGES);
                }
                return stripper.getText(document);
            }
        };
    }
}
