package de.jplag.text;

import de.jplag.options.LanguageOption;
import de.jplag.options.LanguageOptions;
import de.jplag.options.OptionType;

/**
 * Language-specific options for the (natural language) text module. All options are disabled by default, so the module
 * behaves like the naive, language-agnostic word matcher unless paraphrase-oriented normalization is explicitly
 * enabled.
 * <p>
 * The options control how each word is normalized into its {@link TextTokenType}. Since JPlag matches identical token
 * types, collapsing surface variants (inflections, stop words, synonyms) into a shared normalized form lets the core
 * comparison detect paraphrased passages that reuse the same content while changing the wording.
 * <p>
 * Note: lemmatization and synonym canonicalization are backed by WordNet and therefore only meaningful for English
 * text.
 */
public class TextLanguageOptions extends LanguageOptions {

    private final LanguageOption<Boolean> lemmatize = createDefaultOption(OptionType.bool(), "lemmatize",
            "Reduce words to their WordNet base form (e.g. \"running\" -> \"run\") so inflected variants match. English only.", false);

    private final LanguageOption<Boolean> removeStopwords = createDefaultOption(OptionType.bool(), "removeStopwords",
            "Drop common English function words (the, of, is, ...) so inserted filler does not break or dilute matches.", false);

    private final LanguageOption<Boolean> expandSynonyms = createDefaultOption(OptionType.bool(), "expandSynonyms",
            "Map each content word to a canonical WordNet synonym (e.g. \"big\"/\"large\" -> one type) to detect synonym swaps. "
                    + "Implies lemmatization and increases false positives. English only.",
            false);

    /**
     * @return whether words should be reduced to their WordNet base form.
     */
    public boolean lemmatize() {
        return lemmatize.getValue();
    }

    /**
     * @return whether English stop words should be removed from the token stream.
     */
    public boolean removeStopwords() {
        return removeStopwords.getValue();
    }

    /**
     * @return whether content words should be canonicalized to a shared synonym representative.
     */
    public boolean expandSynonyms() {
        return expandSynonyms.getValue();
    }

    /**
     * @return whether any normalization beyond the naive lowercase matching is enabled.
     */
    public boolean isAnyNormalizationEnabled() {
        return lemmatize() || removeStopwords() || expandSynonyms();
    }
}
