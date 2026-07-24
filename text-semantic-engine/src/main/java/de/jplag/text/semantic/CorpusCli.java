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

    private static SemanticEngineConfiguration defaultConfiguration() {
        // Normalization must match between indexing and querying; both use these defaults.
        return SemanticEngineConfiguration.builder().build();
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

        @Override
        public Integer call() throws Exception {
            SemanticEngineConfiguration configuration = defaultConfiguration();
            List<AnalyzedSubmission> submissions = new SubmissionReader(configuration).readSubmissions(documents);
            try (DocumentEmbedder embedder = noEmbeddings ? noEmbedder() : new SbertEmbedder()) {
                LuceneCorpusIndex index = new LuceneCorpusIndex(indexDirectory.toPath(), embedder);
                index.index(submissions);
                logger.info("Indexed {} document(s); corpus now holds {}.", submissions.size(), index.size());
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

        @Override
        public Integer call() throws Exception {
            SemanticEngineConfiguration configuration = defaultConfiguration();
            List<AnalyzedSubmission> queries = new SubmissionReader(configuration).readSubmissions(queryDocuments);
            boolean needsEmbedder = backend != Backend.TFIDF;
            Path indexPath = indexDirectory.toPath();
            try (DocumentEmbedder embedder = needsEmbedder ? new SbertEmbedder() : noEmbedder()) {
                LuceneCorpusIndex index = new LuceneCorpusIndex(indexPath, embedder);
                for (AnalyzedSubmission query : queries) {
                    List<CorpusMatch> matches = index.query(query, backend, topK);
                    System.out.printf("%n%s -- top %d matches (%s):%n", query.name(), matches.size(), backend);
                    for (CorpusMatch match : matches) {
                        System.out.printf("  %.4f  %s%n", match.score(), match.documentId());
                    }
                }
            }
            return 0;
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
