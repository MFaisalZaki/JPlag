package de.jplag.text.semantic;

import java.util.ArrayList;
import java.util.List;

import de.jplag.options.LanguageOption;
import de.jplag.text.NaturalLanguage;
import de.jplag.text.TextLanguageOptions;

/**
 * Immutable configuration for the semantic engine: which normalization to apply during tokenization, which files to
 * accept, the minimum similarity a pair must reach to be reported, and how many explanatory shared terms to include per
 * reported pair.
 */
public class SemanticEngineConfiguration {

    private final TextLanguageOptions normalizationOptions;
    private final List<String> fileExtensions;
    private final double similarityThreshold;
    private final int topSharedTermCount;

    private SemanticEngineConfiguration(Builder builder) {
        this.normalizationOptions = buildNormalizationOptions(builder.lemmatize, builder.removeStopwords, builder.expandSynonyms);
        this.fileExtensions = List.copyOf(builder.fileExtensions);
        this.similarityThreshold = builder.similarityThreshold;
        this.topSharedTermCount = builder.topSharedTermCount;
    }

    private static TextLanguageOptions buildNormalizationOptions(boolean lemmatize, boolean removeStopwords, boolean expandSynonyms) {
        TextLanguageOptions options = new TextLanguageOptions();
        for (LanguageOption<?> option : options.getOptionsAsList()) {
            boolean value = switch (option.getName()) {
                case "lemmatize" -> lemmatize;
                case "removeStopwords" -> removeStopwords;
                case "expandSynonyms" -> expandSynonyms;
                default -> false;
            };
            @SuppressWarnings("unchecked")
            LanguageOption<Boolean> booleanOption = (LanguageOption<Boolean>) option;
            booleanOption.setValue(value);
        }
        return options;
    }

    /**
     * @return the normalization options used during tokenization.
     */
    public TextLanguageOptions normalizationOptions() {
        return normalizationOptions;
    }

    /**
     * @return the accepted file extensions (lower-cased matching is applied by the reader).
     */
    public List<String> fileExtensions() {
        return fileExtensions;
    }

    /**
     * @return the minimum cosine similarity for a pair to be reported.
     */
    public double similarityThreshold() {
        return similarityThreshold;
    }

    /**
     * @return the number of top shared terms to report per pair.
     */
    public int topSharedTermCount() {
        return topSharedTermCount;
    }

    /**
     * @return a builder with paraphrase-oriented defaults (all normalization enabled, threshold 0.5).
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link SemanticEngineConfiguration}.
     */
    public static class Builder {
        private boolean lemmatize = true;
        private boolean removeStopwords = true;
        private boolean expandSynonyms = true;
        private List<String> fileExtensions = defaultFileExtensions();
        private double similarityThreshold = 0.5;
        private int topSharedTermCount = 10;

        /**
         * @return the text module's extensions plus {@code .pdf}, which the engine extracts text from directly.
         */
        private static List<String> defaultFileExtensions() {
            List<String> extensions = new ArrayList<>(new NaturalLanguage().fileExtensions());
            extensions.add(".pdf");
            return extensions;
        }

        /**
         * @param value whether to reduce words to their WordNet base form.
         * @return this builder.
         */
        public Builder lemmatize(boolean value) {
            this.lemmatize = value;
            return this;
        }

        /**
         * @param value whether to remove English stop words.
         * @return this builder.
         */
        public Builder removeStopwords(boolean value) {
            this.removeStopwords = value;
            return this;
        }

        /**
         * @param value whether to canonicalize synonyms via WordNet.
         * @return this builder.
         */
        public Builder expandSynonyms(boolean value) {
            this.expandSynonyms = value;
            return this;
        }

        /**
         * @param extensions the accepted file extensions.
         * @return this builder.
         */
        public Builder fileExtensions(List<String> extensions) {
            this.fileExtensions = extensions;
            return this;
        }

        /**
         * @param threshold the minimum cosine similarity to report.
         * @return this builder.
         */
        public Builder similarityThreshold(double threshold) {
            this.similarityThreshold = threshold;
            return this;
        }

        /**
         * @param count the number of top shared terms to report per pair.
         * @return this builder.
         */
        public Builder topSharedTermCount(int count) {
            this.topSharedTermCount = count;
            return this;
        }

        /**
         * @return the built configuration.
         */
        public SemanticEngineConfiguration build() {
            return new SemanticEngineConfiguration(this);
        }
    }
}
