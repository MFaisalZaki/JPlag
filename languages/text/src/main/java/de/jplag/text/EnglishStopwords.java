package de.jplag.text;

import java.util.Set;

/**
 * A small, fixed list of common English function words. Removing these from the token stream makes matches robust
 * against inserted filler and minor connective rewording, which are typical of paraphrasing.
 */
final class EnglishStopwords {

    private EnglishStopwords() {
        // utility class
    }

    /** The set of common English function words to remove. */
    static final Set<String> WORDS = Set.of("a", "an", "the", "and", "or", "but", "nor", "so", "yet", "for", "of", "to", "in", "on", "at", "by",
            "with", "from", "into", "onto", "upon", "as", "than", "then", "that", "this", "these", "those", "there", "here", "it", "its", "is", "am",
            "are", "was", "were", "be", "been", "being", "do", "does", "did", "have", "has", "had", "having", "will", "would", "shall", "should",
            "can", "could", "may", "might", "must", "not", "no", "if", "else", "when", "while", "because", "about", "above", "below", "up", "down",
            "out", "off", "over", "under", "again", "further", "such", "own", "same", "i", "you", "he", "she", "we", "they", "me", "him", "her", "us",
            "them", "my", "your", "his", "our", "their", "what", "which", "who", "whom", "whose", "how", "all", "any", "both", "each", "few", "more",
            "most", "other", "some", "only", "very", "just", "also");

    /**
     * @param word a lower-cased word.
     * @return whether the word is a common English stop word.
     */
    static boolean contains(String word) {
        return WORDS.contains(word);
    }
}
