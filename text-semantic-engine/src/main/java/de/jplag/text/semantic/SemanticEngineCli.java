package de.jplag.text.semantic;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Command line interface for the standalone semantic text engine.
 * <p>
 * Example: {@code mvn -pl text-semantic-engine exec:java -Dexec.args="/path/to/submissions --threshold 0.6 -o results"}
 */
@Command(name = "jplag-semantic", mixinStandardHelpOptions = true, description = "Detects paraphrased text plagiarism by comparing "
        + "submissions with TF-IDF cosine similarity over WordNet-normalized terms (order-independent).")
public class SemanticEngineCli implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(SemanticEngineCli.class);

    @Parameters(index = "0", description = "Directory containing the submissions (each sub-directory or file is one submission).")
    private File rootDirectory;

    @Option(names = {"-t",
            "--threshold"}, defaultValue = "0.5", description = "Minimum cosine similarity to report (0-1). Default: ${DEFAULT-VALUE}.")
    private double threshold;

    @Option(names = "--top-terms", defaultValue = "10", description = "Number of top shared terms to report per pair. Default: ${DEFAULT-VALUE}.")
    private int topTerms;

    @Option(names = "--backend", defaultValue = "TFIDF", description = "Similarity backend: ${COMPLETION-CANDIDATES}. "
            + "TFIDF is lexical and fast; SBERT is neural (downloads a model on first use); ENSEMBLE combines both. " + "Default: ${DEFAULT-VALUE}.")
    private SemanticEngineConfiguration.Backend backend;

    @Option(names = "--ensemble-weight", description = "For --backend ENSEMBLE, weight on TFIDF in [0,1] for a weighted "
            + "mean (1=pure TFIDF, 0=pure SBERT). If unset, ENSEMBLE uses the max of both.")
    private Double ensembleWeight;

    @Option(names = {"-o", "--output"}, description = "Directory for the JSON/CSV reports. Console only if omitted.")
    private File outputDirectory;

    @Option(names = "--jplag-report", description = "Write a .jplag report archive viewable in the JPlag report viewer.")
    private File jplagReportFile;

    @Option(names = "--lemmatize", negatable = true, defaultValue = "true", description = "Reduce words to their base form. Default: ${DEFAULT-VALUE}.")
    private boolean lemmatize;

    @Option(names = "--remove-stopwords", negatable = true, defaultValue = "true", description = "Drop English stop words. Default: ${DEFAULT-VALUE}.")
    private boolean removeStopwords;

    @Option(names = "--expand-synonyms", negatable = true, defaultValue = "true", description = "Canonicalize synonyms via WordNet. Default: ${DEFAULT-VALUE}.")
    private boolean expandSynonyms;

    @Option(names = "--extensions", split = ",", description = "Comma-separated file extensions to include (default: the text module's extensions).")
    private List<String> extensions;

    @Option(names = "--recursive", description = "Treat every accepted file found recursively as its own submission, "
            + "instead of combining each top-level sub-directory into one submission.")
    private boolean recursive;

    @Override
    public Integer call() throws Exception {
        if (ensembleWeight != null && (ensembleWeight < 0.0 || ensembleWeight > 1.0)) {
            logger.error("--ensemble-weight must be in [0, 1], was {}.", ensembleWeight);
            return 1;
        }
        SemanticEngineConfiguration.Builder builder = SemanticEngineConfiguration.builder().similarityThreshold(threshold)
                .topSharedTermCount(topTerms).lemmatize(lemmatize).removeStopwords(removeStopwords).expandSynonyms(expandSynonyms).backend(backend)
                .ensembleWeight(ensembleWeight);
        if (extensions != null && !extensions.isEmpty()) {
            builder.fileExtensions(extensions);
        }
        SemanticEngineConfiguration configuration = builder.build();

        SubmissionReader reader = new SubmissionReader(configuration);
        List<AnalyzedSubmission> submissions = recursive ? reader.readDocuments(rootDirectory) : reader.readSubmissions(rootDirectory);
        if (submissions.size() < 2) {
            logger.warn("Need at least two non-empty submissions to compare, found {}.", submissions.size());
            return 1;
        }

        List<SubmissionPairSimilarity> results = configuration.createBackend().compare(submissions);
        printSummary(submissions.size(), results);

        if (outputDirectory != null) {
            new ResultWriter().write(results, outputDirectory);
            logger.info("Wrote reports to {}", outputDirectory.getAbsolutePath());
        }
        if (jplagReportFile != null) {
            new JPlagReportWriter().write(submissions, results, jplagReportFile);
            logger.info("Wrote JPlag report to {} (open with the JPlag report viewer)", jplagReportFile.getAbsolutePath());
        }
        return 0;
    }

    private void printSummary(int submissionCount, List<SubmissionPairSimilarity> results) {
        System.out.printf("Analyzed %d submissions. Found %d pair(s) at or above similarity %.2f:%n", submissionCount, results.size(), threshold);
        for (SubmissionPairSimilarity result : results) {
            String terms = result.topSharedTerms().stream().limit(5).map(SharedTerm::term).collect(Collectors.joining(", "));
            System.out.printf("  %.3f  %s <-> %s   [%s]%n", result.similarity(), result.firstSubmission(), result.secondSubmission(), terms);
        }
    }

    /**
     * Entry point.
     * @param args the command line arguments.
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new SemanticEngineCli()).execute(args);
        System.exit(exitCode);
    }
}
