package de.jplag.text.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.CoreSentence;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;

/**
 * A semantic similarity backend using local SBERT sentence embeddings (all-MiniLM-L6-v2 via Deep Java Library).
 * <p>
 * Each submission is split into sentences with CoreNLP {@code ssplit} and every sentence is embedded independently (the
 * model caps at ~256 tokens, so whole essays cannot be embedded at once). Documents are then compared by <em>soft
 * passage alignment</em>: each sentence's best-matching counterpart in the other document, averaged symmetrically.
 * Unlike mean-pooling, this stays high only when passages genuinely align, so it does not wash out on long documents
 * and it detects paraphrases that share meaning rather than vocabulary.
 * <p>
 * The PyTorch native runtime and the model weights are downloaded automatically by DJL on first use.
 */
public class SbertBackend implements SimilarityBackend {

    private static final Logger logger = LoggerFactory.getLogger(SbertBackend.class);
    private static final String MODEL_URL = "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2";
    private static final int MINIMUM_SENTENCE_TOKENS = 3;

    private final SemanticEngineConfiguration configuration;

    /**
     * Creates the backend.
     * @param configuration the engine configuration (supplies the similarity threshold).
     */
    public SbertBackend(SemanticEngineConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public List<SubmissionPairSimilarity> compare(List<AnalyzedSubmission> submissions) {
        StanfordCoreNLP sentencePipeline = createSentencePipeline();
        Criteria<String, float[]> criteria = Criteria.builder().setTypes(String.class, float[].class).optModelUrls(MODEL_URL).optEngine("PyTorch")
                .optTranslatorFactory(new TextEmbeddingTranslatorFactory()).optProgress(new ProgressBar()).build();

        logger.info("Loading SBERT model (all-MiniLM-L6-v2); the model and native runtime download on first use.");
        try (ZooModel<String, float[]> model = criteria.loadModel(); Predictor<String, float[]> predictor = model.newPredictor()) {
            List<List<float[]>> sentenceVectors = new ArrayList<>();
            for (AnalyzedSubmission submission : submissions) {
                sentenceVectors.add(embedSentences(predictor, sentencePipeline, submission.text()));
            }

            List<SubmissionPairSimilarity> results = new ArrayList<>();
            for (int i = 0; i < submissions.size(); i++) {
                for (int j = i + 1; j < submissions.size(); j++) {
                    double similarity = alignmentScore(sentenceVectors.get(i), sentenceVectors.get(j));
                    if (similarity >= configuration.similarityThreshold()) {
                        // SBERT compares meaning, not terms, so no lexical shared-term explanation is produced.
                        results.add(new SubmissionPairSimilarity(submissions.get(i).name(), submissions.get(j).name(), similarity, List.of()));
                    }
                }
            }
            results.sort(Comparator.comparingDouble(SubmissionPairSimilarity::similarity).reversed());
            return results;
        } catch (Exception exception) {
            throw new IllegalStateException("SBERT embedding failed. Ensure the model and PyTorch runtime can be downloaded.", exception);
        }
    }

    private static StanfordCoreNLP createSentencePipeline() {
        Properties properties = new Properties();
        properties.put("annotators", "tokenize,ssplit");
        return new StanfordCoreNLP(properties);
    }

    private static List<float[]> embedSentences(Predictor<String, float[]> predictor, StanfordCoreNLP pipeline, String text) throws Exception {
        List<float[]> vectors = new ArrayList<>();
        CoreDocument document = pipeline.processToCoreDocument(text);
        for (CoreSentence sentence : document.sentences()) {
            if (sentence.tokens().size() >= MINIMUM_SENTENCE_TOKENS) {
                vectors.add(normalize(predictor.predict(sentence.text())));
            }
        }
        return vectors;
    }

    /**
     * Soft passage-alignment similarity: the symmetric average of each sentence's best cosine match in the other document.
     * Returns 0 if either document has no embeddable sentences.
     * @param first sentence embeddings of the first document.
     * @param second sentence embeddings of the second document.
     * @return the alignment similarity in {@code [0, 1]}.
     */
    static double alignmentScore(List<float[]> first, List<float[]> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return 0.0;
        }
        return (meanOfBestMatches(first, second) + meanOfBestMatches(second, first)) / 2.0;
    }

    private static double meanOfBestMatches(List<float[]> from, List<float[]> to) {
        double total = 0.0;
        for (float[] source : from) {
            double best = 0.0;
            for (float[] target : to) {
                best = Math.max(best, cosine(source, target));
            }
            total += best;
        }
        return total / from.size();
    }

    static float[] normalize(float[] vector) {
        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int k = 0; k < vector.length; k++) {
                vector[k] /= (float) norm;
            }
        }
        return vector;
    }

    static double cosine(float[] first, float[] second) {
        double dot = 0.0;
        for (int k = 0; k < first.length; k++) {
            dot += first[k] * second[k];
        }
        return dot;
    }
}
