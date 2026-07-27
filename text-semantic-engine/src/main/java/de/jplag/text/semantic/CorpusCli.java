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

        @Option(names = "--author-pattern", defaultValue = "", description = "Regex applied to each document's file name to extract "
                + "its author (first capture group, or the whole match). Lets authors differ per file, e.g. '^([0-9]+)-' for "
                + "'<studentid>-essay.pdf'; files it does not match fall back to --author.")
        private String authorPattern;

        @Option(names = "--extensions", split = ",", description = "Comma-separated file extensions to include, searched "
                + "recursively (with or without a leading dot). Default: the text module's extensions plus .pdf.")
        private List<String> extensions;

        @Override
        public Integer call() throws Exception {
            SemanticEngineConfiguration configuration = configuration(extensions);
            List<AnalyzedSubmission> submissions = new SubmissionReader(configuration).readDocuments(documents);
            AuthorResolver authorResolver = new AuthorResolver(author, authorPattern);
            try (DocumentEmbedder embedder = noEmbeddings ? noEmbedder() : new SbertEmbedder()) {
                LuceneCorpusIndex index = new LuceneCorpusIndex(indexDirectory.toPath(), embedder);
                index.index(submissions, submission -> authorResolver.authorOf(submission.name()));
                long authored = submissions.stream().filter(submission -> !authorResolver.authorOf(submission.name()).isBlank()).count();
                logger.info("Indexed {} document(s){}; corpus now holds {}.", submissions.size(),
                        authored == 0 ? "" : " (" + authored + " with an author)", index.size());
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

        @Option(names = "--sentence-threshold", defaultValue = "0.85", description = "Sentence cosine similarity to count as a "
                + "match in the HTML report. Embeddings rate any two sentences on one topic highly, so values near 0.7 report "
                + "shared subject matter rather than reuse. Default: ${DEFAULT-VALUE}.")
        private double sentenceThreshold;

        @Option(names = "--min-lexical-overlap", defaultValue = "0.10", description = "Distinctive wording a match must share "
                + "with its source, as a word overlap weighted by how rare each word is across the documents compared, so a "
                + "cohort's shared topic vocabulary does not count as evidence. 0 reports semantic similarity alone. " + "Default: ${DEFAULT-VALUE}.")
        private double minimumLexicalOverlap;

        @Option(names = "--max-source-fraction", defaultValue = "0.75", description = "Share of the candidate sources a passage "
                + "may match before it is treated as material they all share (a common citation, a standard definition) rather "
                + "than reuse from any one of them. 1 disables the check. Default: ${DEFAULT-VALUE}.")
        private double maximumSourceFraction;

        @Option(names = "--include-boilerplate", description = "Match and count administrative front matter — assignment cover "
                + "sheets and academic-integrity declarations. Excluded by default: it is identical in every submission of a "
                + "cohort, so it matches near-perfectly and outranks genuine matches.")
        private boolean includeBoilerplate;

        @Option(names = "--show-attributed", description = "Also highlight quoted/cited matches (de-emphasized). By default "
                + "they are hidden and excluded from the score, since acknowledged reuse is not plagiarism.")
        private boolean showAttributed;

        @Option(names = "--author", defaultValue = "", description = "Author of the query document(s); matches to the same "
                + "author's indexed work are flagged as self-plagiarism.")
        private String queryAuthor;

        @Option(names = "--author-pattern", defaultValue = "", description = "Regex applied to each query document's file name to "
                + "extract its author (first capture group, or the whole match). Lets authors differ per file, e.g. '^([0-9]+)-' "
                + "for '<studentid>-essay.pdf'; files it does not match fall back to --author.")
        private String authorPattern;

        @Option(names = "--exclude-same-author", negatable = true, defaultValue = "true", fallbackValue = "true", description = "Never "
                + "match a document against its own author's other indexed work (e.g. a resubmission of the same essay). On by "
                + "default; needs authors in the index (--author/--author-pattern at index time) and on the query. Use "
                + "--no-exclude-same-author to keep such matches, flagged as self-plagiarism instead.")
        private boolean excludeSameAuthor;

        @Option(names = "--extensions", split = ",", description = "Comma-separated file extensions to include, searched "
                + "recursively (with or without a leading dot). Default: the text module's extensions plus .pdf.")
        private List<String> extensions;

        @Override
        public Integer call() throws Exception {
            SemanticEngineConfiguration configuration = configuration(extensions);
            List<AnalyzedSubmission> queries = new SubmissionReader(configuration).readDocuments(queryDocuments);
            AuthorResolver authorResolver = new AuthorResolver(queryAuthor, authorPattern);
            boolean needsSbert = backend != Backend.TFIDF || htmlReportDirectory != null;
            Path indexPath = indexDirectory.toPath();
            SbertEmbedder sbert = needsSbert ? new SbertEmbedder() : null;
            try (DocumentEmbedder embedder = sbert != null ? sbert : noEmbedder()) {
                LuceneCorpusIndex index = new LuceneCorpusIndex(indexPath, embedder);
                OriginalityReportGenerator reportGenerator = sbert == null ? null
                        : new OriginalityReportGenerator(sentenceThreshold, sbert::embedSentencesWithText, !showAttributed, minimumLexicalOverlap,
                                maximumSourceFraction, !includeBoilerplate);
                for (AnalyzedSubmission query : queries) {
                    String author = authorResolver.authorOf(query.name());
                    List<CorpusMatch> matches = index.query(query, backend, topK, excludeSameAuthor ? author : "");
                    List<ArchivedDocument> sources = reportGenerator != null || !author.isBlank()
                            ? index.documents(matches.stream().map(CorpusMatch::documentId).toList())
                            : java.util.List.of();
                    java.util.Map<String, String> authorOf = new java.util.HashMap<>();
                    sources.forEach(source -> authorOf.put(source.id(), source.author()));

                    System.out.printf("%n%s -- top %d matches (%s):%n", query.name(), matches.size(), backend);
                    for (CorpusMatch match : matches) {
                        boolean self = !author.isBlank() && author.equals(authorOf.get(match.documentId()));
                        System.out.printf("  %.4f  %s%s%n", match.score(), match.documentId(), self ? "  [SELF-PLAGIARISM]" : "");
                    }
                    if (reportGenerator != null && htmlReportDirectory != null) {
                        writeHtmlReport(reportGenerator, query, sources, author);
                    }
                }
            }
            return 0;
        }

        private void writeHtmlReport(OriginalityReportGenerator generator, AnalyzedSubmission query, List<ArchivedDocument> sources, String author)
                throws java.io.IOException {
            String html = generator.generate(query.name(), query.text(), sources, author);
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
