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

    @Option(names = {"-o", "--output"}, description = "Directory for the JSON/CSV reports. Console only if omitted.")
    private File outputDirectory;

    @Option(names = "--lemmatize", negatable = true, defaultValue = "true", description = "Reduce words to their base form. Default: ${DEFAULT-VALUE}.")
    private boolean lemmatize;

    @Option(names = "--remove-stopwords", negatable = true, defaultValue = "true", description = "Drop English stop words. Default: ${DEFAULT-VALUE}.")
    private boolean removeStopwords;

    @Option(names = "--expand-synonyms", negatable = true, defaultValue = "true", description = "Canonicalize synonyms via WordNet. Default: ${DEFAULT-VALUE}.")
    private boolean expandSynonyms;

    @Option(names = "--extensions", split = ",", description = "Comma-separated file extensions to include (default: the text module's extensions).")
    private List<String> extensions;

    @Override
    public Integer call() throws Exception {
        SemanticEngineConfiguration.Builder builder = SemanticEngineConfiguration.builder().similarityThreshold(threshold)
                .topSharedTermCount(topTerms).lemmatize(lemmatize).removeStopwords(removeStopwords).expandSynonyms(expandSynonyms);
        if (extensions != null && !extensions.isEmpty()) {
            builder.fileExtensions(extensions);
        }
        SemanticEngineConfiguration configuration = builder.build();

        List<AnalyzedSubmission> submissions = new SubmissionReader(configuration).readSubmissions(rootDirectory);
        if (submissions.size() < 2) {
            logger.warn("Need at least two non-empty submissions to compare, found {}.", submissions.size());
            return 1;
        }

        List<SubmissionPairSimilarity> results = new SemanticComparisonEngine(configuration).compare(submissions);
        printSummary(submissions.size(), results);

        if (outputDirectory != null) {
            new ResultWriter().write(results, outputDirectory);
            logger.info("Wrote reports to {}", outputDirectory.getAbsolutePath());
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
