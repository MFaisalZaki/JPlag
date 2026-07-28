package de.jplag.text;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.extjwnl.JWNLException;
import net.sf.extjwnl.data.IndexWord;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.data.Synset;
import net.sf.extjwnl.dictionary.Dictionary;

/**
 * Normalizes the surface form of a word into the string that becomes its {@link TextTokenType}. Because JPlag matches
 * identical token types, collapsing inflections and synonyms into a shared form is what lets the core comparison detect
 * paraphrased text.
 * <p>
 * The normalization steps (all optional, see {@link TextLanguageOptions}) are applied in this order per word:
 * <ol>
 * <li>lower-casing (always),</li>
 * <li>stop-word removal (the word is dropped entirely),</li>
 * <li>synonym canonicalization (maps the word to a canonical WordNet synonym; implies lemmatization), or</li>
 * <li>lemmatization (maps the word to its WordNet base form).</li>
 * </ol>
 * Lemmatization and synonym canonicalization use WordNet and are therefore English-specific. If WordNet cannot be
 * loaded, these steps are disabled and a warning is logged; the module then falls back to lower-cased (and optionally
 * stop-word-filtered) matching.
 */
public class TextNormalizer {

    private static final Logger logger = LoggerFactory.getLogger(TextNormalizer.class);

    /** Part-of-speech tags tried in order when looking a word up in WordNet (no POS tagger is used). */
    private static final List<POS> POS_LOOKUP_ORDER = List.of(POS.NOUN, POS.VERB, POS.ADJECTIVE, POS.ADVERB);

    /** Most {@code -}/{@code _} separated parts a word may have and still be looked up; see {@link #isWordLike}. */
    private static final int MAX_LEMMA_PARTS = 4;

    private final boolean lemmatize;
    private final boolean removeStopwords;
    private final boolean expandSynonyms;
    private final Dictionary dictionary;

    /**
     * Creates a normalizer for the given options. WordNet is loaded lazily only if lemmatization or synonym expansion is
     * requested.
     * @param options the configured text language options.
     */
    public TextNormalizer(TextLanguageOptions options) {
        this.removeStopwords = options.removeStopwords();
        // synonym expansion always implies lemmatization, as WordNet lookup resolves the base form anyway
        this.lemmatize = options.lemmatize() || options.expandSynonyms();
        this.expandSynonyms = options.expandSynonyms();
        this.dictionary = this.lemmatize ? loadDictionary() : null;
    }

    private static Dictionary loadDictionary() {
        try {
            return Dictionary.getDefaultResourceInstance();
        } catch (JWNLException exception) {
            logger.warn("Could not load WordNet dictionary. Lemmatization and synonym expansion are disabled.", exception);
            return null;
        }
    }

    /**
     * Normalizes a single word.
     * @param originalText the original word text.
     * @return the normalized token description, or {@link Optional#empty()} if the word should be dropped (stop word).
     */
    public Optional<String> normalize(String originalText) {
        String word = originalText.toLowerCase(Locale.ENGLISH);
        if (removeStopwords && EnglishStopwords.contains(word)) {
            return Optional.empty();
        }
        if (dictionary == null) {
            return Optional.of(word);
        }
        if (expandSynonyms) {
            return Optional.of(canonicalSynonym(word));
        }
        return Optional.of(baseForm(word));
    }

    /**
     * Reduces a word to its WordNet base form, e.g. "running" to "run". Returns the original word if it is not found.
     */
    private String baseForm(String word) {
        IndexWord indexWord = lookup(word);
        return indexWord == null ? word : indexWord.getLemma();
    }

    /**
     * Maps a word to a canonical representative shared by all words in its (most common) WordNet sense, so that synonyms
     * collapse to the same token type. Falls back to the base form (then the original word) if no sense is found.
     */
    private String canonicalSynonym(String word) {
        IndexWord indexWord = lookup(word);
        if (indexWord == null || indexWord.getSenses().isEmpty()) {
            return baseForm(word);
        }
        Synset firstSense = indexWord.getSenses().get(0);
        if (firstSense.getWords().isEmpty()) {
            return indexWord.getLemma();
        }
        // Every word in a synset shares the same first word, guaranteeing all synonyms map to the same representative.
        return firstSense.getWords().get(0).getLemma().toLowerCase(Locale.ENGLISH);
    }

    /**
     * Looks a word up in WordNet, applying morphological reduction, trying each part of speech in
     * {@link #POS_LOOKUP_ORDER}.
     */
    private IndexWord lookup(String word) {
        if (!isWordLike(word)) {
            return null;
        }
        for (POS pos : POS_LOOKUP_ORDER) {
            try {
                IndexWord indexWord = dictionary.lookupIndexWord(pos, word);
                if (indexWord != null) {
                    return indexWord;
                }
            } catch (JWNLException exception) {
                logger.debug("WordNet lookup failed for word '{}' as {}.", word, pos, exception);
            }
        }
        return null;
    }

    /**
     * Whether the word is worth looking up in WordNet at all.
     * <p>
     * Single characters are excluded because initials like "T." or "C." otherwise map to unrelated synsets (thymine,
     * celsius, ...). The other two rules bound the cost: WordNet stores multi-word lemmas separated by {@code _}, so its
     * morphological processor splits a word on {@code -} and {@code _} and tries <em>every</em> regrouping of the parts —
     * work that doubles with each additional part. A URL or file name pasted into a document is a single token to the
     * tokenizer but no kind of English word, and a long one (measured: 19 parts takes over an hour) stalls the parse on its
     * own. Genuine hyphenated words stay well inside {@link #MAX_LEMMA_PARTS}, e.g. "state-of-the-art".
     */
    private static boolean isWordLike(String word) {
        if (word.length() < 2) {
            return false;
        }
        int parts = 1;
        for (int i = 0; i < word.length(); i++) {
            char character = word.charAt(i);
            if (character == '/' || character == ':') { // a path or a URI scheme, never a lemma
                return false;
            }
            if ((character == '-' || character == '_') && ++parts > MAX_LEMMA_PARTS) {
                return false;
            }
        }
        return true;
    }
}
