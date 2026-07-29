package de.jplag.text.semantic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Command line interface for building and querying a persistent corpus index, for cross-referencing new documents
 * against a large archive of past documents.
 * <p>
 * Build/extend: {@code index --index INDEX_DIR DOCUMENTS}. Query:
 * {@code query --index INDEX_DIR --query NEW_DOCUMENTS --html-report REPORTS}.
 */
@Command(name = "jplag-corpus", mixinStandardHelpOptions = true, subcommands = {CorpusCli.IndexCommand.class,
        CorpusCli.QueryCommand.class}, description = "Build and query a persistent index of documents for cross-referencing an archive.")
public class CorpusCli implements Runnable {

    private static final String EXTENSIONS_DESCRIPTION = "Comma-separated file extensions to include, searched recursively "
            + "(with or without a leading dot). Default: the text module's extensions plus .pdf.";
    private static final String COAUTHOR_DESCRIPTION = "Regex whose every match on the first 2000 characters of a document "
            + "(its cover sheet) is a co-author, e.g. '\\b2[0-9]{8}\\b' for student ids. Needed for paired or group "
            + "courseworks, where each member submits the same document and the file name names only the submitter.";

    private static SubmissionReader reader(List<String> extensions) {
        return new SubmissionReader(extensions == null || extensions.isEmpty() ? SubmissionReader.defaultFileExtensions() : extensions);
    }

    /** Stand-in for the SBERT embedder when only the lexical (BM25) signal is needed, so no model is loaded. */
    private static DocumentEmbedder noEmbedder() {
        return text -> new float[1];
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

        @Parameters(index = "0", description = "Directory of documents to add; every accepted file below it is one document.")
        private File documents;

        @Option(names = "--no-embeddings", description = "Index only the lexical (BM25) field; skip SBERT embeddings and the model download.")
        private boolean noEmbeddings;

        @Option(names = "--author", defaultValue = "", description = "Author of these documents; enables self-plagiarism detection at query time.")
        private String author;

        @Option(names = "--author-pattern", defaultValue = "", description = "Regex applied to each document's file name to extract "
                + "its author (first capture group, or the whole match). Lets authors differ per file, e.g. '^([0-9]+)-' for "
                + "'<studentid>-essay.pdf'; files it does not match fall back to --author.")
        private String authorPattern;

        @Option(names = "--coauthor-pattern", defaultValue = "", description = COAUTHOR_DESCRIPTION)
        private String coauthorPattern;

        @Option(names = "--extensions", split = ",", description = EXTENSIONS_DESCRIPTION)
        private List<String> extensions;

        @Override
        public Integer call() throws Exception {
            List<AnalyzedSubmission> submissions = reader(extensions).readDocuments(documents);
            AuthorResolver authorResolver = new AuthorResolver(author, authorPattern, coauthorPattern);
            try (DocumentEmbedder embedder = noEmbeddings ? noEmbedder() : new SbertEmbedder()) {
                LuceneCorpusIndex index = new LuceneCorpusIndex(indexDirectory.toPath(), embedder);
                index.index(submissions, submission -> authorResolver.authorsOf(submission.name(), submission.text()));
                long authored = submissions.stream().filter(submission -> !authorResolver.authorsOf(submission.name(), submission.text()).isEmpty())
                        .count();
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

        @Option(names = "--query", required = true, description = "Directory of query documents; every accepted file below it is one.")
        private File queryDocuments;

        @Option(names = "--backend", defaultValue = "ENSEMBLE", description = "Retrieval signal: ${COMPLETION-CANDIDATES} "
                + "(TFIDF=BM25, SBERT=vector, ENSEMBLE=both fused). Default: ${DEFAULT-VALUE}.")
        private Backend backend;

        @Option(names = "--top-k", defaultValue = "0", description = "Archived documents to compare each query against, most "
                + "similar first. 0 (the default) compares against the whole index, so nothing is missed because retrieval "
                + "ranked it low; retrieval then only orders the results. Set a limit for a corpus too large to compare in "
                + "full — cost grows with the number of documents actually compared. Default: ${DEFAULT-VALUE}.")
        private int topK;

        @Option(names = "--html-report", description = "Directory to write a Turnitin-style HTML originality report per query "
                + "document (highlighted matches + sources + overall score). Requires SBERT.")
        private File htmlReportDirectory;

        @Option(names = "--sentence-threshold", defaultValue = "0.85", description = "Sentence cosine similarity to count as a "
                + "match in the HTML report. Embeddings rate any two sentences on one topic highly, so values near 0.7 report "
                + "shared subject matter rather than reuse. Default: ${DEFAULT-VALUE}.")
        private double sentenceThreshold;

        @Option(names = "--minimum-word-overlap", defaultValue = "0", description = "Literal word overlap (Jaccard, 0-1) a "
                + "match must reach in addition to the sentence threshold. 0 (the default) lets semantic similarity alone "
                + "decide, which is right when the sources are the cohort's own submissions. Raise it — 0.4 for lightly "
                + "edited wording, 0.8 for near-verbatim — when the index holds published or reference material, where a "
                + "subject's standard sentences match everyone who wrote about it correctly. Default: ${DEFAULT-VALUE}.")
        private double minimumWordOverlap;

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

        @Option(names = "--coauthor-pattern", defaultValue = "", description = COAUTHOR_DESCRIPTION)
        private String coauthorPattern;

        @Option(names = "--exclude-same-author", negatable = true, defaultValue = "true", fallbackValue = "true", description = "Never "
                + "match a document against its own author's other indexed work (e.g. a resubmission of the same essay). On by "
                + "default; needs authors in the index (--author/--author-pattern at index time) and on the query. Use "
                + "--no-exclude-same-author to keep such matches, flagged as self-plagiarism instead.")
        private boolean excludeSameAuthor;

        @Option(names = "--extensions", split = ",", description = EXTENSIONS_DESCRIPTION)
        private List<String> extensions;

        @Override
        public Integer call() throws Exception {
            List<AnalyzedSubmission> queries = reader(extensions).readDocuments(queryDocuments);
            AuthorResolver authorResolver = new AuthorResolver(queryAuthor, authorPattern, coauthorPattern);
            boolean needsSbert = backend != Backend.TFIDF || htmlReportDirectory != null;
            SbertEmbedder sbert = needsSbert ? new SbertEmbedder() : null;
            try (DocumentEmbedder embedder = sbert != null ? sbert : noEmbedder()) {
                LuceneCorpusIndex index = new LuceneCorpusIndex(indexDirectory.toPath(), embedder);
                OriginalityReportGenerator reportGenerator = sbert == null ? null
                        : new OriginalityReportGenerator(sentenceThreshold, minimumWordOverlap, sbert::embedSentencesWithText, !showAttributed);
                // Comparing against the whole index is "top-k where k is the corpus size", so retrieval still ranks the
                // results; it just no longer decides which documents get compared at all.
                int comparedDocuments = topK > 0 ? topK : index.size();
                if (topK <= 0) {
                    System.out.printf("Comparing each query against all %d indexed document(s).%n", comparedDocuments);
                }
                for (AnalyzedSubmission query : queries) {
                    report(index, reportGenerator, query, authorResolver.authorsOf(query.name(), query.text()), comparedDocuments);
                }
            }
            return 0;
        }

        private void report(LuceneCorpusIndex index, OriginalityReportGenerator generator, AnalyzedSubmission query, Set<String> authors,
                int comparedDocuments) throws IOException {
            List<CorpusMatch> matches = index.query(query, backend, comparedDocuments, excludeSameAuthor ? authors : Set.of());
            List<ArchivedDocument> sources = generator != null || !authors.isEmpty()
                    ? index.documents(matches.stream().map(CorpusMatch::documentId).toList())
                    : List.of();
            Map<String, Set<String>> authorsOf = new HashMap<>();
            sources.forEach(source -> authorsOf.put(source.id(), source.authors()));

            System.out.printf("%n%s -- top %d matches (%s):%n", query.name(), matches.size(), backend);
            for (CorpusMatch match : matches) {
                boolean self = !Collections.disjoint(authors, authorsOf.getOrDefault(match.documentId(), Set.of()));
                System.out.printf("  %.4f  %s%s%n", match.score(), match.documentId(), self ? "  [SELF-PLAGIARISM]" : "");
            }
            if (generator != null && htmlReportDirectory != null) {
                Files.createDirectories(htmlReportDirectory.toPath());
                Path output = htmlReportDirectory.toPath().resolve(query.name() + ".html");
                Files.writeString(output, generator.generate(query.name(), query.text(), sources, authors));
                System.out.printf("  -> HTML report: %s%n", output.toAbsolutePath());
            }
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
