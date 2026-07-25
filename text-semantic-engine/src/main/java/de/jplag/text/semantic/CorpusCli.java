package de.jplag.text.semantic;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jplag.text.semantic.SemanticEngineConfiguration.Backend;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Command line interface for building and querying a persistent corpus index, for cross-referencing a new document
 * against a large archive of past documents.
 * <p>
 * Build/extend: {@code index --index INDEX_DIR DOCUMENTS}. Query:
 * {@code query --index INDEX_DIR --query NEW_DOCUMENTS --backend ENSEMBLE}.
 */
@Command(name = "jplag-corpus", mixinStandardHelpOptions = true, subcommands = {CorpusCli.IndexCommand.class,
        CorpusCli.QueryCommand.class}, description = "Build and query a persistent index of documents for cross-referencing an archive.")
public class CorpusCli implements Runnable {

    private static SemanticEngineConfiguration configuration(List<String> extensions) {
        // Normalization must match between indexing and querying; both use the builder defaults.
        SemanticEngineConfiguration.Builder builder = SemanticEngineConfiguration.builder();
        if (extensions != null && !extensions.isEmpty()) {
            builder.fileExtensions(extensions);
        }
        return builder.build();
    }

    private static DocumentEmbedder noEmbedder() {
        return new DocumentEmbedder() {
            @Override
            public float[] embed(String text) {
                return new float[1];
            }

            @Override
            public int dimension() {
                return 1;
            }
        };
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    /**
     * Adds documents to the index (creating it if needed).
     */
    @Command(name = "index", mixinStandardHelpOptions = true, description = "Parse, embed, and add documents to the corpus index.")
    public static class IndexCommand implements Callable<Integer> {

        private static final Logger logger = LoggerFactory.getLogger(IndexCommand.class);

        @Option(names = "--index", required = true, description = "Directory holding the Lucene corpus index.")
        private File indexDirectory;

        @Parameters(index = "0", description = "Directory of documents to add (each sub-directory or file is one document).")
        private File documents;

        @Option(names = "--no-embeddings", description = "Index only the lexical (BM25) field; skip SBERT embeddings and the model download.")
        private boolean noEmbeddings;

        @Option(names = "--author", defaultValue = "", description = "Author of these documents; enables self-plagiarism detection at query time.")
        private String author;

        @Option(names = "--extensions", split = ",", description = "Comma-separated file extensions to include, searched "
                + "recursively (with or without a leading dot). Default: the text module's extensions plus .pdf.")
        private List<String> extensions;

        @Override
        public Integer call() throws Exception {
            SemanticEngineConfiguration configuration = configuration(extensions);
            List<AnalyzedSubmission> submissions = new SubmissionReader(configuration).readDocuments(documents);
            try (DocumentEmbedder embedder = noEmbeddings ? noEmbedder() : new SbertEmbedder()) {
                LuceneCorpusIndex index = new LuceneCorpusIndex(indexDirectory.toPath(), embedder);
                index.index(submissions, author);
                logger.info("Indexed {} document(s){}; corpus now holds {}.", submissions.size(), author.isBlank() ? "" : " by '" + author + "'",
                        index.size());
            }
            return 0;
        }
    }

    /**
     * Cross-references query documents against the index.
     */
    @Command(name = "query", mixinStandardHelpOptions = true, description = "Find the archived documents most similar to each query document.")
    public static class QueryCommand implements Callable<Integer> {

        @Option(names = "--index", required = true, description = "Directory holding the Lucene corpus index.")
        private File indexDirectory;

        @Option(names = "--query", required = true, description = "Directory of query documents (each sub-directory or file is one).")
        private File queryDocuments;

        @Option(names = "--backend", defaultValue = "ENSEMBLE", description = "Retrieval signal: ${COMPLETION-CANDIDATES} "
                + "(TFIDF=BM25, SBERT=vector, ENSEMBLE=both fused). Default: ${DEFAULT-VALUE}.")
        private Backend backend;

        @Option(names = "--top-k", defaultValue = "10", description = "Number of matches to report per query document. Default: ${DEFAULT-VALUE}.")
        private int topK;

        @Option(names = "--html-report", description = "Directory to write a Turnitin-style HTML originality report per query "
                + "document (highlighted matches + sources + overall score). Requires SBERT.")
        private File htmlReportDirectory;

        @Option(names = "--sentence-threshold", defaultValue = "0.7", description = "Sentence cosine similarity to count as a "
                + "match in the HTML report. Default: ${DEFAULT-VALUE}.")
        private double sentenceThreshold;

        @Option(names = "--show-attributed", description = "Also highlight quoted/cited matches (de-emphasized). By default "
                + "they are hidden and excluded from the score, since acknowledged reuse is not plagiarism.")
        private boolean showAttributed;

        @Option(names = "--author", defaultValue = "", description = "Author of the query document(s); matches to the same "
                + "author's indexed work are flagged as self-plagiarism.")
        private String queryAuthor;

        @Option(names = "--extensions", split = ",", description = "Comma-separated file extensions to include, searched "
                + "recursively (with or without a leading dot). Default: the text module's extensions plus .pdf.")
        private List<String> extensions;

        @Override
        public Integer call() throws Exception {
            SemanticEngineConfiguration configuration = configuration(extensions);
            List<AnalyzedSubmission> queries = new SubmissionReader(configuration).readDocuments(queryDocuments);
            boolean needsSbert = backend != Backend.TFIDF || htmlReportDirectory != null;
            Path indexPath = indexDirectory.toPath();
            SbertEmbedder sbert = needsSbert ? new SbertEmbedder() : null;
            try (DocumentEmbedder embedder = sbert != null ? sbert : noEmbedder()) {
                LuceneCorpusIndex index = new LuceneCorpusIndex(indexPath, embedder);
                OriginalityReportGenerator reportGenerator = sbert == null ? null
                        : new OriginalityReportGenerator(sentenceThreshold, sbert::embedSentencesWithText, !showAttributed);
                for (AnalyzedSubmission query : queries) {
                    List<CorpusMatch> matches = index.query(query, backend, topK);
                    List<ArchivedDocument> sources = reportGenerator != null || !queryAuthor.isBlank()
                            ? index.documents(matches.stream().map(CorpusMatch::documentId).toList())
                            : java.util.List.of();
                    java.util.Map<String, String> authorOf = new java.util.HashMap<>();
                    sources.forEach(source -> authorOf.put(source.id(), source.author()));

                    System.out.printf("%n%s -- top %d matches (%s):%n", query.name(), matches.size(), backend);
                    for (CorpusMatch match : matches) {
                        boolean self = !queryAuthor.isBlank() && queryAuthor.equals(authorOf.get(match.documentId()));
                        System.out.printf("  %.4f  %s%s%n", match.score(), match.documentId(), self ? "  [SELF-PLAGIARISM]" : "");
                    }
                    if (reportGenerator != null && htmlReportDirectory != null) {
                        writeHtmlReport(reportGenerator, query, sources);
                    }
                }
            }
            return 0;
        }

        private void writeHtmlReport(OriginalityReportGenerator generator, AnalyzedSubmission query, List<ArchivedDocument> sources)
                throws java.io.IOException {
            String html = generator.generate(query.name(), query.text(), sources, queryAuthor);
            java.nio.file.Files.createDirectories(htmlReportDirectory.toPath());
            java.nio.file.Path output = htmlReportDirectory.toPath().resolve(query.name() + ".html");
            java.nio.file.Files.writeString(output, html);
            System.out.printf("  -> HTML report: %s%n", output.toAbsolutePath());
        }
    }

    /**
     * Entry point.
     * @param args the command line arguments.
     */
    public static void main(String[] args) {
        System.exit(new CommandLine(new CorpusCli()).execute(args));
    }
}
