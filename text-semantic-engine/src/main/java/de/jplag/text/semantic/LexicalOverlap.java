package de.jplag.text.semantic;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Measures how much <em>distinctive</em> wording two passages share, by weighting each word by how rare it is among the
 * documents being compared.
 * <p>
 * This exists to corroborate semantic matches. Sentence embeddings score any two sentences on the same topic highly
 * even when they state unrelated things, so cosine similarity alone cannot separate reuse from a shared subject: in one
 * cohort of same-prompt essays, 63 of 152 reported matches shared no distinctive word at all with the passage they
 * supposedly copied. Requiring some shared wording removes those, and weighting by rarity is what makes the requirement
 * fair — in a cohort all writing about biodiversity, sharing "biodiversity" is no evidence, while sharing "polyculture"
 * is.
 * <p>
 * The weight is an inverse document frequency, {@code ln(N / (df + 0.5))} floored at zero, so a word occurring in every
 * document contributes nothing and cannot by itself sustain a match. Deliberately unsmoothed: the usual {@code + 1}
 * would leave ubiquitous words carrying weight, which is exactly the noise this is meant to discount. Because the
 * frequencies come from the documents at hand — the query and its candidate sources — the discounting adapts to each
 * cohort's own vocabulary instead of relying on a fixed stop-word list.
 * <p>
 * Below {@value #MINIMUM_DOCUMENTS_FOR_WEIGHTING} documents there is no basis for calling one word rarer than another —
 * with two documents, every word they share occurs in "all" of them — so the weighting is dropped and a plain Jaccard
 * coefficient is used. Shared wording is still required; only the discounting of common vocabulary is lost.
 */
public final class LexicalOverlap {

    /** Documents needed before word frequencies say anything about which words are distinctive. */
    private static final int MINIMUM_DOCUMENTS_FOR_WEIGHTING = 4;

    private final Map<String, Double> weights;

    private LexicalOverlap(Map<String, Double> weights) {
        this.weights = weights;
    }

    /**
     * Derives word weights from the documents being compared.
     * @param documents the texts to take word frequencies from; normally the query document and its candidate sources.
     * @return the measure, weighted so that words common to all the documents carry no evidential value, or unweighted if
     * too few documents were supplied to establish that.
     */
    public static LexicalOverlap fromDocuments(Collection<String> documents) {
        if (documents.size() < MINIMUM_DOCUMENTS_FOR_WEIGHTING) {
            return new LexicalOverlap(Map.of());
        }
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (String document : documents) {
            for (String word : words(document)) {
                documentFrequency.merge(word, 1, Integer::sum);
            }
        }
        int total = documents.size();
        Map<String, Double> weights = new HashMap<>();
        documentFrequency.forEach((word, frequency) -> weights.put(word, Math.max(0.0, Math.log(total / (frequency + 0.5)))));
        return new LexicalOverlap(weights);
    }

    /**
     * Scores the shared wording of two passages as a weighted Jaccard coefficient of their word sets.
     * @param first the first passage.
     * @param second the second passage.
     * @return the share of the two passages' combined distinctive wording that they have in common, in {@code [0, 1]}; zero
     * when neither passage contains a word rare enough to carry weight.
     */
    public double score(String first, String second) {
        Set<String> a = words(first);
        Set<String> b = words(second);
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        double shared = 0.0;
        double combined = 0.0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        for (String word : union) {
            double weight = weights.isEmpty() ? 1.0 : weights.getOrDefault(word, 0.0);
            combined += weight;
            if (a.contains(word) && b.contains(word)) {
                shared += weight;
            }
        }
        return combined == 0.0 ? 0.0 : shared / combined;
    }

    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        if (text == null) {
            return words;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 1) {
                words.add(token);
            }
        }
        return words;
    }
}
