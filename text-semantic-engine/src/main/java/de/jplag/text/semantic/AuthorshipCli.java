package de.jplag.text.semantic;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Command line interface for authorship verification by writing style (stylometry), to help spot ghostwriting /
 * contract cheating. Given the known writing of candidate authors and a query document, it ranks the authors by how
 * closely their style matches and flags when the closest style is not the claimed author.
 * <p>
 * Example: {@code jplag-authorship --known authors --query submission.txt --claimed alice}, where {@code authors} has
 * one sub-directory per author containing that author's prior documents.
 */
@Command(name = "jplag-authorship", mixinStandardHelpOptions = true, description = "Verify authorship by writing style (Burrows's Delta) "
        + "to help detect ghostwriting.")
public class AuthorshipCli implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(AuthorshipCli.class);

    @Option(names = "--known", required = true, description = "Directory with one sub-directory per candidate author (their known documents).")
    private File knownDirectory;

    @Option(names = "--query", required = true, description = "Directory of query documents to attribute (each sub-directory or file is one).")
    private File queryDirectory;

    @Option(names = "--claimed", description = "The claimed author of the query document(s); flagged if it is not the closest style.")
    private String claimedAuthor;

    @Option(names = "--most-frequent-words", defaultValue = "150", description = "Number of most-frequent words used as style "
            + "features. Default: ${DEFAULT-VALUE}.")
    private int mostFrequentWords;

    @Override
    public Integer call() throws Exception {
        // Normalization off: stylometry needs the raw function words (stop words), which normalization would remove.
        SemanticEngineConfiguration configuration = SemanticEngineConfiguration.builder().lemmatize(false).removeStopwords(false)
                .expandSynonyms(false).build();
        SubmissionReader reader = new SubmissionReader(configuration);

        Map<String, String> authorTexts = new LinkedHashMap<>();
        for (AnalyzedSubmission author : reader.readSubmissions(knownDirectory)) {
            authorTexts.put(author.name(), author.text());
        }
        if (authorTexts.size() < 2) {
            logger.error("Need at least two candidate authors in {}, found {}.", knownDirectory, authorTexts.size());
            return 1;
        }
        List<AnalyzedSubmission> queries = reader.readSubmissions(queryDirectory);
        StylometryAnalyzer analyzer = new StylometryAnalyzer(mostFrequentWords);

        for (AnalyzedSubmission query : queries) {
            List<StylometryAnalyzer.AuthorScore> ranked = analyzer.rank(authorTexts, query.text());
            System.out.printf("%n%s -- closest writing style (lower Delta = more similar):%n", query.name());
            for (StylometryAnalyzer.AuthorScore score : ranked) {
                boolean claimed = score.author().equals(claimedAuthor);
                System.out.printf("  %.3f  %s%s%n", score.delta(), score.author(), claimed ? "  (claimed)" : "");
            }
            reportVerdict(query.name(), ranked);
        }
        return 0;
    }

    private void reportVerdict(String queryName, List<StylometryAnalyzer.AuthorScore> ranked) {
        String closest = ranked.get(0).author();
        if (claimedAuthor == null) {
            System.out.printf("  => closest style: %s%n", closest);
        } else if (closest.equals(claimedAuthor)) {
            System.out.printf("  => consistent with claimed author '%s'%n", claimedAuthor);
        } else {
            System.out.printf("  => WARNING: style of '%s' is closest to '%s', not the claimed author '%s' (possible ghostwriting)%n", queryName,
                    closest, claimedAuthor);
        }
    }

    /**
     * Entry point.
     * @param args the command line arguments.
     */
    public static void main(String[] args) {
        System.exit(new CommandLine(new AuthorshipCli()).execute(args));
    }
}
