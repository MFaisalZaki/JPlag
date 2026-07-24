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

    /**
     * The similarity backend to use.
     */
    public enum Backend {
        /** Lexical TF-IDF cosine over WordNet-normalized terms (fast, pure JVM, order-independent). */
        TFIDF,
        /** Neural SBERT sentence embeddings with passage alignment (semantic; downloads a model on first use). */
        SBERT,
        /** Maximum of the TFIDF and SBERT scores per pair (flags a pair if either signal is strong). */
        ENSEMBLE
    }

    private final TextLanguageOptions normalizationOptions;
    private final List<String> fileExtensions;
    private final double similarityThreshold;
    private final int topSharedTermCount;
    private final Backend backend;
    private final Double ensembleWeight;

    private SemanticEngineConfiguration(Builder builder) {
        this(buildNormalizationOptions(builder.lemmatize, builder.removeStopwords, builder.expandSynonyms), List.copyOf(builder.fileExtensions),
                builder.similarityThreshold, builder.topSharedTermCount, builder.backend, builder.ensembleWeight);
    }

    private SemanticEngineConfiguration(TextLanguageOptions normalizationOptions, List<String> fileExtensions, double similarityThreshold,
            int topSharedTermCount, Backend backend, Double ensembleWeight) {
        this.normalizationOptions = normalizationOptions;
        this.fileExtensions = fileExtensions;
        this.similarityThreshold = similarityThreshold;
        this.topSharedTermCount = topSharedTermCount;
        this.backend = backend;
        this.ensembleWeight = ensembleWeight;
    }

    /**
     * Returns a copy of this configuration with a different similarity threshold. Used by the ensemble to run its delegates
     * unfiltered (threshold 0) so it has every pair's score to combine.
     * @param threshold the new similarity threshold.
     * @return a copy with the given threshold.
     */
    SemanticEngineConfiguration withSimilarityThreshold(double threshold) {
        return new SemanticEngineConfiguration(normalizationOptions, fileExtensions, threshold, topSharedTermCount, backend, ensembleWeight);
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
     * @return the similarity backend to use.
     */
    public Backend backend() {
        return backend;
    }

    /**
     * @return the ensemble weight on the TF-IDF score in {@code [0, 1]} ({@code 1} = pure TF-IDF, {@code 0} = pure SBERT),
     * or {@code null} to combine by maximum instead of a weighted mean.
     */
    public Double ensembleWeight() {
        return ensembleWeight;
    }

    /**
     * Creates the configured similarity backend.
     * @return a new backend instance.
     */
    public SimilarityBackend createBackend() {
        return switch (backend) {
            case TFIDF -> new SemanticComparisonEngine(this);
            case SBERT -> new SbertBackend(this);
            case ENSEMBLE -> new EnsembleBackend(this);
        };
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
        private Backend backend = Backend.TFIDF;
        private Double ensembleWeight = null;

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
         * @param backend the similarity backend to use.
         * @return this builder.
         */
        public Builder backend(Backend backend) {
            this.backend = backend;
            return this;
        }

        /**
         * @param weight the ensemble weight on TF-IDF in {@code [0, 1]}, or {@code null} to combine by maximum.
         * @return this builder.
         */
        public Builder ensembleWeight(Double weight) {
            this.ensembleWeight = weight;
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
