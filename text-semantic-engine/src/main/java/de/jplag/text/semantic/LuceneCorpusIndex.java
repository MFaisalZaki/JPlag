package de.jplag.text.semantic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import de.jplag.text.semantic.SemanticEngineConfiguration.Backend;

/**
 * A persistent, queryable corpus index backed by Apache Lucene, for cross-referencing a new document against a large
 * archive (e.g. years of past submissions) without re-parsing or re-embedding it each time.
 * <p>
 * Each archived document is stored once with a BM25 inverted index over its normalized terms (lexical retrieval) and an
 * HNSW dense-vector field over its embedding (semantic retrieval). A query retrieves candidates by BM25, by vector
 * nearest-neighbour, or by both fused with reciprocal-rank fusion (RRF) — which combines the two rankings without
 * having to reconcile their different score scales. The index is incremental: new documents are added (or updated by
 * id) without rebuilding.
 */
public class LuceneCorpusIndex {

    private static final String FIELD_ID = "id";
    private static final String FIELD_AUTHOR = "author";
    private static final String FIELD_TERMS = "terms";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_TEXT = "text";
    /** Cap on distinct query terms, kept under Lucene's default BooleanQuery clause limit. */
    private static final int MAX_QUERY_TERMS = 1000;
    /** Reciprocal-rank-fusion constant (dampens the influence of low ranks); 60 is the conventional value. */
    private static final int RRF_K = 60;
    /** Candidate pool retrieved from each ranker before fusion/truncation. */
    private static final int CANDIDATE_FACTOR = 5;

    private final Path indexDirectory;
    private final DocumentEmbedder embedder;

    /**
     * Creates the index handle.
     * @param indexDirectory the directory holding the Lucene index.
     * @param embedder the embedder used for the vector field and for query vectors.
     */
    public LuceneCorpusIndex(Path indexDirectory, DocumentEmbedder embedder) {
        this.indexDirectory = indexDirectory;
        this.embedder = embedder;
    }

    /**
     * Adds (or updates by id) the given documents to the index with an unknown author.
     * @param documents the analyzed documents to index.
     * @throws IOException if writing the index fails.
     */
    public void index(Collection<AnalyzedSubmission> documents) throws IOException {
        index(documents, "");
    }

    /**
     * Adds (or updates by id) the given documents to the index, attributing them to the given author (used to detect
     * self-plagiarism at query time).
     * @param documents the analyzed documents to index.
     * @param author the author of these documents (empty if unknown).
     * @throws IOException if writing the index fails.
     */
    public void index(Collection<AnalyzedSubmission> documents, String author) throws IOException {
        try (Directory directory = FSDirectory.open(indexDirectory);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new WhitespaceAnalyzer()))) {
            for (AnalyzedSubmission document : documents) {
                writer.updateDocument(new Term(FIELD_ID, document.name()), toLuceneDocument(document, author));
            }
        }
    }

    private Document toLuceneDocument(AnalyzedSubmission submission, String author) {
        Document document = new Document();
        document.add(new StringField(FIELD_ID, submission.name(), Field.Store.YES));
        document.add(new StringField(FIELD_AUTHOR, author, Field.Store.YES));
        document.add(new TextField(FIELD_TERMS, expandTerms(submission.termFrequencies()), Field.Store.NO));
        document.add(new StoredField(FIELD_TEXT, submission.text()));
        float[] embedding = embedder.embed(submission.text());
        if (isNonZero(embedding)) { // Lucene cosine vectors must be non-zero
            document.add(new KnnFloatVectorField(FIELD_VECTOR, embedding, VectorSimilarityFunction.COSINE));
        }
        return document;
    }

    /**
     * Cross-references a query document against the index, returning the most similar archived documents.
     * @param query the analyzed query document.
     * @param backend which signal(s) to use: TFIDF (BM25), SBERT (vector), or ENSEMBLE (RRF of both).
     * @param topK the number of matches to return.
     * @return the matches, most similar first (excluding any document with the query's own id).
     * @throws IOException if reading the index fails.
     */
    public List<CorpusMatch> query(AnalyzedSubmission query, Backend backend, int topK) throws IOException {
        try (Directory directory = FSDirectory.open(indexDirectory); DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            int candidates = topK * CANDIDATE_FACTOR;
            return switch (backend) {
                case TFIDF -> lexical(searcher, query, topK);
                case SBERT -> semantic(searcher, query, topK);
                case ENSEMBLE -> fuse(lexical(searcher, query, candidates), semantic(searcher, query, candidates), topK, query.name());
            };
        }
    }

    /**
     * Retrieves the stored text of the given documents (for showing/aligning matched passages in a report).
     * @param ids the document ids to fetch.
     * @return the archived documents that exist, with their stored text, in the order requested.
     * @throws IOException if reading the index fails.
     */
    public List<ArchivedDocument> documents(List<String> ids) throws IOException {
        List<ArchivedDocument> documents = new ArrayList<>();
        try (Directory directory = FSDirectory.open(indexDirectory); DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            StoredFields storedFields = searcher.storedFields();
            for (String id : ids) {
                TopDocs topDocs = searcher.search(new TermQuery(new Term(FIELD_ID, id)), 1);
                if (topDocs.scoreDocs.length > 0) {
                    Document stored = storedFields.document(topDocs.scoreDocs[0].doc);
                    String author = stored.get(FIELD_AUTHOR);
                    String text = stored.get(FIELD_TEXT);
                    documents.add(new ArchivedDocument(id, author == null ? "" : author, text == null ? "" : text));
                }
            }
        }
        return documents;
    }

    private List<CorpusMatch> lexical(IndexSearcher searcher, AnalyzedSubmission query, int topK) throws IOException {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        query.termFrequencies().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(MAX_QUERY_TERMS)
                .forEach(entry -> builder.add(new TermQuery(new Term(FIELD_TERMS, entry.getKey())), BooleanClause.Occur.SHOULD));
        return search(searcher, builder.build(), topK, query.name(), "lexical");
    }

    private List<CorpusMatch> semantic(IndexSearcher searcher, AnalyzedSubmission query, int topK) throws IOException {
        float[] vector = embedder.embed(query.text());
        if (!isNonZero(vector)) {
            return List.of();
        }
        return search(searcher, new KnnFloatVectorQuery(FIELD_VECTOR, vector, topK + 1), topK, query.name(), "semantic");
    }

    private List<CorpusMatch> search(IndexSearcher searcher, org.apache.lucene.search.Query query, int topK, String excludeId, String source)
            throws IOException {
        TopDocs topDocs = searcher.search(query, topK + 1);
        StoredFields storedFields = searcher.storedFields();
        List<CorpusMatch> matches = new ArrayList<>();
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            String id = storedFields.document(scoreDoc.doc).get(FIELD_ID);
            if (!id.equals(excludeId)) { // never match a document against itself
                matches.add(new CorpusMatch(id, scoreDoc.score, source));
            }
            if (matches.size() == topK) {
                break;
            }
        }
        return matches;
    }

    private static List<CorpusMatch> fuse(List<CorpusMatch> lexical, List<CorpusMatch> semantic, int topK, String excludeId) {
        Map<String, Double> fused = new HashMap<>();
        accumulateReciprocalRank(fused, lexical);
        accumulateReciprocalRank(fused, semantic);
        return fused.entrySet().stream().filter(entry -> !entry.getKey().equals(excludeId))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(topK)
                .map(entry -> new CorpusMatch(entry.getKey(), entry.getValue(), "ensemble")).toList();
    }

    private static void accumulateReciprocalRank(Map<String, Double> fused, List<CorpusMatch> ranking) {
        for (int rank = 0; rank < ranking.size(); rank++) {
            fused.merge(ranking.get(rank).documentId(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
    }

    private static String expandTerms(Map<String, Integer> termFrequencies) {
        StringBuilder builder = new StringBuilder();
        termFrequencies.forEach((term, count) -> {
            for (int i = 0; i < count; i++) {
                builder.append(term).append(' ');
            }
        });
        return builder.toString();
    }

    private static boolean isNonZero(float[] vector) {
        for (float value : vector) {
            if (value != 0.0f) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the number of documents currently in the index.
     * @throws IOException if reading the index fails.
     */
    public int size() throws IOException {
        try (Directory directory = FSDirectory.open(indexDirectory)) {
            if (!DirectoryReader.indexExists(directory)) {
                return 0;
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                return reader.numDocs();
            }
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
