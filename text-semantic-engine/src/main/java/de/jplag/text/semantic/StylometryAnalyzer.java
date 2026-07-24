package de.jplag.text.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attributes authorship by writing style using <a href="https://en.wikipedia.org/wiki/Stylometry">Burrows's Delta</a>.
 * <p>
 * Unlike the plagiarism detectors in this module, this does not look for reused content. It compares <em>style</em>:
 * the relative frequencies of the most common words (dominated by topic-independent function words like "the", "of",
 * "however"), which authors use consistently and largely unconsciously. Each candidate author and the query document
 * are turned into a z-scored frequency vector over the corpus's most frequent words; Delta is the mean absolute
 * difference of those z-scores. The candidate with the smallest Delta is the closest stylistic match — so if a
 * submission's claimed author is not the closest, its style is inconsistent with that author (a possible ghostwriter).
 */
public class StylometryAnalyzer {

    private static final Pattern WORD = Pattern.compile("[a-z]+(?:'[a-z]+)?");

    private final int mostFrequentWords;

    /**
     * @param mostFrequentWords how many of the corpus's most frequent words to use as style features (e.g. 150).
     */
    public StylometryAnalyzer(int mostFrequentWords) {
        this.mostFrequentWords = mostFrequentWords;
    }

    /**
     * A candidate author's stylistic distance to the query document (lower means more similar style).
     * @param author the candidate author.
     * @param delta the Burrows's Delta distance.
     */
    public record AuthorScore(String author, double delta) {
    }

    /**
     * Ranks candidate authors by how closely their writing style matches the query document.
     * @param authorTexts the known text of each candidate author (author name to combined sample text).
     * @param queryText the text of the document to attribute.
     * @return the candidate authors sorted by ascending Delta (closest style first).
     */
    public List<AuthorScore> rank(Map<String, String> authorTexts, String queryText) {
        Map<String, List<String>> authorTokens = new LinkedHashMap<>();
        authorTexts.forEach((author, text) -> authorTokens.put(author, tokenize(text)));
        List<String> queryTokens = tokenize(queryText);

        List<String> features = mostFrequentWords(authorTokens.values());
        Map<String, double[]> authorFrequencies = new LinkedHashMap<>();
        authorTokens.forEach((author, tokens) -> authorFrequencies.put(author, relativeFrequencies(tokens, features)));
        double[] queryFrequencies = relativeFrequencies(queryTokens, features);

        double[] mean = new double[features.size()];
        double[] standardDeviation = new double[features.size()];
        computeStatistics(authorFrequencies.values(), mean, standardDeviation);

        List<AuthorScore> scores = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : authorFrequencies.entrySet()) {
            scores.add(new AuthorScore(entry.getKey(), delta(queryFrequencies, entry.getValue(), mean, standardDeviation)));
        }
        scores.sort(Comparator.comparingDouble(AuthorScore::delta));
        return scores;
    }

    private static double delta(double[] query, double[] author, double[] mean, double[] standardDeviation) {
        double sum = 0.0;
        int used = 0;
        for (int i = 0; i < mean.length; i++) {
            if (standardDeviation[i] == 0.0) {
                continue; // a word with no variation across authors carries no stylistic signal
            }
            double queryZ = (query[i] - mean[i]) / standardDeviation[i];
            double authorZ = (author[i] - mean[i]) / standardDeviation[i];
            sum += Math.abs(queryZ - authorZ);
            used++;
        }
        return used == 0 ? 0.0 : sum / used;
    }

    private static void computeStatistics(Iterable<double[]> vectors, double[] mean, double[] standardDeviation) {
        int count = 0;
        for (double[] vector : vectors) {
            for (int i = 0; i < mean.length; i++) {
                mean[i] += vector[i];
            }
            count++;
        }
        for (int i = 0; i < mean.length; i++) {
            mean[i] /= count;
        }
        for (double[] vector : vectors) {
            for (int i = 0; i < mean.length; i++) {
                standardDeviation[i] += (vector[i] - mean[i]) * (vector[i] - mean[i]);
            }
        }
        for (int i = 0; i < mean.length; i++) {
            standardDeviation[i] = Math.sqrt(standardDeviation[i] / count);
        }
    }

    private List<String> mostFrequentWords(Iterable<List<String>> tokenLists) {
        Map<String, Long> frequencies = new HashMap<>();
        for (List<String> tokens : tokenLists) {
            for (String token : tokens) {
                frequencies.merge(token, 1L, Long::sum);
            }
        }
        return frequencies.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(mostFrequentWords)
                .map(Map.Entry::getKey).toList();
    }

    private static double[] relativeFrequencies(List<String> tokens, List<String> features) {
        Map<String, Long> counts = new HashMap<>();
        for (String token : tokens) {
            counts.merge(token, 1L, Long::sum);
        }
        double[] frequencies = new double[features.size()];
        if (tokens.isEmpty()) {
            return frequencies;
        }
        for (int i = 0; i < features.size(); i++) {
            frequencies[i] = counts.getOrDefault(features.get(i), 0L) / (double) tokens.size();
        }
        return frequencies;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }
}
