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
import edu.stanford.nlp.util.Pair;

/**
 * A long-lived SBERT embedder (all-MiniLM-L6-v2 via DJL) that keeps the model loaded so it can embed many documents
 * while building or querying a corpus index. Text is split into sentences (CoreNLP {@code ssplit}) and each sentence is
 * embedded separately, since the model caps at ~256 tokens; a document embedding is the mean of its sentence
 * embeddings, L2-normalized. The model and PyTorch runtime download automatically on first use.
 */
public class SbertEmbedder implements DocumentEmbedder {

    private static final String MODEL_URL = "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2";
    /**
     * Sentences shorter than this are dropped. Sentence splitting turns headings, table cells, dates and the shards of a
     * reference entry into their own "sentences"; they embed unreliably (too little context for the model to place them)
     * and they match each other across documents for no interesting reason, so they are noise in a report rather than
     * evidence. Counted in CoreNLP tokens, so punctuation counts too.
     */
    private static final int MINIMUM_SENTENCE_TOKENS = 10;

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
     * Embeds every sentence of the text, keeping each sentence's text and its position in the document alongside its
     * unit-length vector. Sentences too short to embed reliably are dropped; their character range simply stays unclaimed,
     * so a report can still render that part of the document as ordinary text.
     * @param text the document text.
     * @return the embedded sentences, in document order.
     * @throws IllegalStateException if embedding fails.
     */
    public List<EmbeddedSentence> embedSentencesWithText(String text) {
        List<EmbeddedSentence> sentences = new ArrayList<>();
        CoreDocument document = sentencePipeline.processToCoreDocument(text);
        for (CoreSentence sentence : document.sentences()) {
            if (sentence.tokens().size() >= MINIMUM_SENTENCE_TOKENS) {
                Pair<Integer, Integer> offsets = sentence.charOffsets();
                sentences.add(new EmbeddedSentence(sentence.text(), embedSentence(sentence.text()), offsets.first(), offsets.second()));
            }
        }
        return sentences;
    }

    @Override
    public float[] embed(String text) {
        float[] sum = new float[dimension];
        for (EmbeddedSentence sentence : embedSentencesWithText(text)) {
            for (int k = 0; k < dimension; k++) {
                sum[k] += sentence.vector()[k];
            }
        }
        return normalize(sum);
    }

    private float[] embedSentence(String sentence) {
        try {
            return normalize(predictor.predict(sentence));
        } catch (Exception exception) {
            throw new IllegalStateException("SBERT embedding failed.", exception);
        }
    }

    /** Scales a vector to unit length, so that a dot product of two such vectors is their cosine similarity. */
    private static float[] normalize(float[] vector) {
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

    @Override
    public void close() {
        predictor.close();
        model.close();
    }
}
