package de.jplag.text.semantic;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Counts how many documents of a cohort contain each sentence, so that sentences shared by much of the cohort can be
 * ignored when checking for reuse.
 * <p>
 * A sentence that turns up in a tenth of a class's submissions is the assignment brief, the prescribed lab procedure,
 * or the module description — text every student was given, not text anyone copied from a peer. This is sentence-level
 * inverse document frequency: the retrieval side already discounts common <em>terms</em>, but the sentence alignment
 * that produces the report score does not, which is why shared template prose dominates it.
 * <p>
 * Sentences are compared on a normalized key (lower-cased letters and digits, first {@value #KEY_WORDS} words), so
 * near-duplicates that differ in punctuation, spacing, or a trailing clause still count as the same sentence.
 */
public final class SentenceFrequency {

    /** Words of a sentence that form its key; enough to identify it, short enough to survive small edits. */
    private static final int KEY_WORDS = 12;
    /** Sentences shorter than this are not counted: too short to identify, and already dropped as match candidates. */
    private static final int MINIMUM_KEY_WORDS = 4;
    /** Documents a sentence must appear in before its share is meaningful at all. */
    private static final int MINIMUM_DOCUMENTS_PER_SENTENCE = 3;
    /** Cohort size below which document frequency says nothing and the filter switches itself off. */
    private static final int MINIMUM_COHORT = 10;

    private final Map<String, Integer> documentsPerSentence;
    private final int documents;
    private final double maximumShare;

    private SentenceFrequency(Map<String, Integer> documentsPerSentence, int documents, double maximumShare) {
        this.documentsPerSentence = documentsPerSentence;
        this.documents = documents;
        this.maximumShare = maximumShare;
    }

    /**
     * Counts the sentences of a cohort.
     * @param texts the cohort's document texts.
     * @param splitter splits a text into sentences (no embedding needed).
     * @param maximumShare the share of the cohort above which a sentence counts as common; 0 or less disables the filter.
     * @return the sentence frequencies, or a disabled instance if the share is not positive or the cohort is too small.
     */
    public static SentenceFrequency of(Collection<String> texts, Function<String, List<String>> splitter, double maximumShare) {
        if (maximumShare <= 0 || texts.size() < MINIMUM_COHORT) {
            return disabled();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String text : texts) {
            Set<String> keys = new HashSet<>();
            for (String sentence : splitter.apply(text)) {
                key(sentence).ifPresent(keys::add);
            }
            keys.forEach(key -> counts.merge(key, 1, Integer::sum));
        }
        return new SentenceFrequency(counts, texts.size(), maximumShare);
    }

    /**
     * @return an instance that never reports a sentence as common.
     */
    public static SentenceFrequency disabled() {
        return new SentenceFrequency(Map.of(), 0, 0);
    }

    /**
     * @param sentence the sentence to check.
     * @return whether this sentence appears in enough of the cohort to be shared material rather than anyone's own writing.
     */
    public boolean isCommon(String sentence) {
        if (documents == 0) {
            return false;
        }
        return key(sentence).map(key -> {
            int count = documentsPerSentence.getOrDefault(key, 0);
            return count >= MINIMUM_DOCUMENTS_PER_SENTENCE && count >= maximumShare * documents;
        }).orElse(false);
    }

    /**
     * @return whether this instance actually filters anything (the cohort was big enough and a share was set).
     */
    public boolean isEnabled() {
        return documents > 0;
    }

    /** The normalized key of a sentence, or empty if it is too short to identify. */
    private static Optional<String> key(String sentence) {
        StringBuilder key = new StringBuilder();
        int words = 0;
        for (String token : sentence.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                if (words > 0) {
                    key.append(' ');
                }
                key.append(token);
                if (++words == KEY_WORDS) {
                    break;
                }
            }
        }
        return words < MINIMUM_KEY_WORDS ? Optional.empty() : Optional.of(key.toString());
    }
}
