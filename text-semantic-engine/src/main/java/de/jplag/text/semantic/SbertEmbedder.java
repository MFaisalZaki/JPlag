package de.jplag.text.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.CoreSentence;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;

/**
 * A long-lived SBERT embedder (all-MiniLM-L6-v2 via DJL) that keeps the model loaded so it can embed many documents,
 * e.g. while building or querying a corpus index. A document embedding is the mean of its sentence embeddings (CoreNLP
 * {@code ssplit}), L2-normalized. The model and PyTorch runtime download automatically on first use.
 */
public class SbertEmbedder implements DocumentEmbedder {

    private static final String MODEL_URL = "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2";
    private static final int MINIMUM_SENTENCE_TOKENS = 3;

    private final StanfordCoreNLP sentencePipeline;
    private final ZooModel<String, float[]> model;
    private final Predictor<String, float[]> predictor;
    private final int dimension;

    /**
     * Loads the model (downloading it on first use).
     * @throws IllegalStateException if the model or PyTorch runtime cannot be loaded.
     */
    public SbertEmbedder() {
        Properties properties = new Properties();
        properties.put("annotators", "tokenize,ssplit");
        this.sentencePipeline = new StanfordCoreNLP(properties);

        Criteria<String, float[]> criteria = Criteria.builder().setTypes(String.class, float[].class).optModelUrls(MODEL_URL).optEngine("PyTorch")
                .optTranslatorFactory(new TextEmbeddingTranslatorFactory()).optProgress(new ProgressBar()).build();
        try {
            this.model = criteria.loadModel();
            this.predictor = model.newPredictor();
            this.dimension = predictor.predict("dimension probe").length;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load the SBERT model. Ensure it and the PyTorch runtime can be downloaded.", exception);
        }
    }

    /**
     * Embeds every sentence of the text into a unit-length vector.
     * @param text the document text.
     * @return the sentence embeddings.
     * @throws IllegalStateException if embedding fails.
     */
    public List<float[]> embedSentences(String text) {
        List<float[]> vectors = new ArrayList<>();
        CoreDocument document = sentencePipeline.processToCoreDocument(text);
        for (CoreSentence sentence : document.sentences()) {
            if (sentence.tokens().size() >= MINIMUM_SENTENCE_TOKENS) {
                try {
                    vectors.add(SbertBackend.normalize(predictor.predict(sentence.text())));
                } catch (Exception exception) {
                    throw new IllegalStateException("SBERT embedding failed.", exception);
                }
            }
        }
        return vectors;
    }

    /**
     * Embeds every sentence of the text, keeping the sentence text alongside its vector.
     * @param text the document text.
     * @return the embedded sentences.
     * @throws IllegalStateException if embedding fails.
     */
    public List<EmbeddedSentence> embedSentencesWithText(String text) {
        List<EmbeddedSentence> sentences = new ArrayList<>();
        CoreDocument document = sentencePipeline.processToCoreDocument(text);
        for (CoreSentence sentence : document.sentences()) {
            if (sentence.tokens().size() >= MINIMUM_SENTENCE_TOKENS) {
                try {
                    sentences.add(new EmbeddedSentence(sentence.text(), SbertBackend.normalize(predictor.predict(sentence.text()))));
                } catch (Exception exception) {
                    throw new IllegalStateException("SBERT embedding failed.", exception);
                }
            }
        }
        return sentences;
    }

    @Override
    public float[] embed(String text) {
        List<float[]> sentences = embedSentences(text);
        float[] sum = new float[dimension];
        for (float[] vector : sentences) {
            for (int k = 0; k < dimension; k++) {
                sum[k] += vector[k];
            }
        }
        return SbertBackend.normalize(sum);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public void close() {
        predictor.close();
        model.close();
    }
}
