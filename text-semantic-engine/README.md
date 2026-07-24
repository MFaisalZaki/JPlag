# JPlag Semantic Text Engine

A **standalone, order-independent** plagiarism engine for natural-language text. Instead of JPlag's token-based Greedy String Tiling (GST), it compares documents by **TF-IDF cosine similarity over WordNet-normalized terms**. Because it works on bags of normalized terms rather than contiguous token runs, it detects paraphrasing that **reorders and restructures** content — the case GST and the [text language module](../languages/text/README.md) cannot handle.

It **does not modify the JPlag comparison core**. It reuses the text module for tokenization and normalization and runs as its own tool with its own JSON/CSV report.

## When to use which

| Approach | Catches | Misses | Where |
|---|---|---|---|
| **Core GST** (`text` module, default) | (near-)verbatim reuse | any synonym/inflection change | [languages/text](../languages/text/README.md) |
| **Text module + normalization flags** | + synonyms, inflections (order-preserving) | reordering, restructuring | [languages/text](../languages/text/README.md) |
| **This engine** | + reordering, restructuring, synonyms, inflections | deep semantic rewrites with no shared vocabulary | here |

All three keep data **on your machine** (no external API calls).

## How it works

```
submissions ──▶ tokenize + normalize ──▶ TF-IDF vectors ──▶ all-pairs cosine ──▶ ranked report
              (reuses text module's       (order-free bag    (order-independent   (JSON + CSV +
               WordNet normalization)       of terms)          similarity)          console)
```

1. **Ingest** — each sub-directory (files combined) or single file under the root is one submission.
2. **Normalize** — delegates to the text module's `ParserAdapter`, so the same WordNet lemmatization, stop-word removal, and synonym canonicalization apply (all **on by default** here).
3. **Vectorize** — sub-linear TF × smoothed IDF, L2-normalized ([`TfIdfVectorizer`](src/main/java/de/jplag/text/semantic/TfIdfVectorizer.java)).
4. **Compare** — cosine similarity for every pair; report those at/above the threshold, ranked, each with the **top shared terms** that explain the score.

## Backends

The engine has two interchangeable similarity backends (`--backend`):

| Backend | How it compares | Catches | Cost |
|---|---|---|---|
| **TFIDF** (default) | lexical: TF-IDF cosine over WordNet-normalized terms | reordering + synonyms + inflection | pure JVM, instant |
| **SBERT** | semantic: local sentence embeddings (all-MiniLM-L6-v2), compared by passage alignment | the above **plus** rewrites that share meaning without sharing vocabulary | downloads a model + PyTorch runtime (~a few hundred MB) on first use; slower |
| **ENSEMBLE** | the **max** of the TFIDF and SBERT scores per pair (or a weighted mean, see `--ensemble-weight`) | flags a pair if *either* signal is strong | runs both (so includes SBERT's cost) |

The **SBERT** backend splits each document into sentences (CoreNLP `ssplit`), embeds each sentence locally, and scores a pair by *soft passage alignment* — each sentence's best-matching counterpart in the other document, averaged symmetrically (which, unlike mean-pooling, does not wash out on long documents). The model and PyTorch native runtime are fetched automatically by [Deep Java Library](https://djl.ai/) the first time you run it. SBERT produces document-level scores rather than token matches, so it does not emit shared-term explanations.

Which to use: **TFIDF** is the strong, cheap default and wins when paraphrases keep vocabulary. Reach for **SBERT** when you expect genuine rewording where lexical overlap collapses, or **ENSEMBLE** to cover both threat models at once.

By default ENSEMBLE combines by **maximum**. Because SBERT scores run higher than TF-IDF, that max often tracks the SBERT score (inheriting its higher noise floor). Pass **`--ensemble-weight <0..1>`** to combine by a weighted mean instead — `w * tfidf + (1 - w) * sbert`, where `1` is pure TF-IDF and `0` is pure SBERT. A weight leaning toward TF-IDF (e.g. `0.6`) pulls semantically-inflated scores on low-lexical-overlap pairs back down, trading coverage for a cleaner margin. Example:

```bash
mvn -pl text-semantic-engine exec:java -Dexec.args="/path/to/submissions --backend SBERT --threshold 0.4"
```

## Input layout

Point the engine at a directory whose children are the submissions:

```
submissions/
├── alice/              # a submission = a folder (all its text files combined)
│   ├── part1.txt
│   └── part2.md
├── bob.txt             # or a submission = a single file
└── carol.txt
```

Accepted extensions default to the text module's (`.txt`, `.md`, `.tex`, `.csv`, `.json`, `.yaml`, `.xml`, …) **plus `.pdf`**; override with `--extensions`.

**PDF support:** unlike the core JPlag `text` module (which reads only plain text), this engine ingests `.pdf` files directly — text is extracted on the fly with Apache PDFBox, so no manual `pdftotext` step is needed.

## Usage

Run via Maven from the repository root (no separate build step needed):

```bash
mvn -pl text-semantic-engine exec:java \
  -Dexec.args="/path/to/submissions --threshold 0.6 -o results"
```

### Options

| Option | Default | Description |
|---|---|---|
| `<directory>` | (required) | Root directory containing the submissions. |
| `--backend <TFIDF\|SBERT>` | `TFIDF` | Similarity backend (see below). |
| `-t`, `--threshold <0-1>` | `0.5` | Minimum similarity for a pair to be reported. |
| `--top-terms <n>` | `10` | Number of explanatory shared terms per reported pair. |
| `-o`, `--output <dir>` | (console only) | Directory to write `semantic-results.json` and `semantic-results.csv`. |
| `--jplag-report <file>` | (none) | Write a `.jplag` archive that opens in the JPlag report viewer. |
| `--[no-]lemmatize` | on | Reduce words to their WordNet base form. |
| `--[no-]remove-stopwords` | on | Drop English stop words. |
| `--[no-]expand-synonyms` | on | Canonicalize synonyms via WordNet. |
| `--extensions <a,b,…>` | text module's | Comma-separated file extensions to include. |
| `-h`, `--help` | | Show help. |

Disable a normalization step with its `--no-` form, e.g. `--no-expand-synonyms`.

## Example

Three submissions — two describe a sorting algorithm (one a heavy paraphrase of the other: reordered sentences, `clever→smart`, `large→big`, `adjacent→neighbouring`, `swapping→exchanges`), one is about photosynthesis:

```
$ mvn -pl text-semantic-engine exec:java -Dexec.args="submissions --threshold 0.1 -o out"

Analyzed 3 submissions. Found 1 pair(s) at or above similarity 0.10:
  0.382  student_a <-> student_b   [repeatedly, comparison, large, quickly, list]
```

The paraphrase pair is flagged; the unrelated document is correctly excluded. GST would score that pair near zero.

`out/semantic-results.json`:

```json
[ {
  "firstSubmission" : "student_a",
  "secondSubmission" : "student_b",
  "similarity" : 0.3819,
  "topSharedTerms" : [
    { "term" : "repeatedly", "contribution" : 0.0477 },
    { "term" : "comparison", "contribution" : 0.0477 }
  ]
} ]
```

`out/semantic-results.csv`:

```csv
first_submission,second_submission,similarity,top_shared_terms
student_a,student_b,0.3819,repeatedly comparison large quickly list array until order
```

## Viewing results in the JPlag report viewer

Pass `--jplag-report <file>.jplag` to emit a report archive in JPlag's native format, then open it in the [JPlag report viewer](https://jplag.github.io/JPlag/):

```bash
mvn -pl text-semantic-engine exec:java \
  -Dexec.args="/path/to/submissions --threshold 0.3 --jplag-report results.jplag"
```

The engine's cosine score is written into the `AVG` metric, which the viewer sorts and displays by default, so you get the ranked pair list and scores in the familiar UI. It reuses JPlag's own `ZipWriter` and report DTO records ([`JPlagReportWriter`](src/main/java/de/jplag/text/semantic/JPlagReportWriter.java)) so the archive stays format-compatible (report version ≥ 6.2.0).

**Note:** because this engine produces document-level similarities rather than token matches, comparisons are written with an empty `matches` array — the viewer shows the pairs and scores but no in-text highlighting. Use the JSON/CSV output (`-o`) for the explanatory top shared terms.

## Cross-referencing against an archive (corpus index)

The batch mode above compares a set of submissions against each other (all pairs, O(n²)). To instead check a new document against a **large, growing archive** (e.g. years of past submissions), use the persistent **corpus index** — build it once, query in ~log time, and never re-parse or re-embed the archive.

It is backed by [Apache Lucene](https://lucene.apache.org/): each archived document is stored with a **BM25 inverted index** over its normalized terms (lexical retrieval) and an **HNSW dense-vector field** over its SBERT embedding (semantic retrieval). A query retrieves by BM25, by vector nearest-neighbour, or by both fused with **reciprocal-rank fusion (RRF)** — which combines the two rankings without reconciling their different score scales. The index is incremental (documents are added/updated by id).

```bash
# Build or extend the index (parses + embeds; downloads the SBERT model on first use).
mvn -pl text-semantic-engine exec:java -Dexec.mainClass=de.jplag.text.semantic.CorpusCli \
  -Dexec.args="index --index /path/to/index /path/to/archive"

# Cross-reference new documents against the archive.
mvn -pl text-semantic-engine exec:java -Dexec.mainClass=de.jplag.text.semantic.CorpusCli \
  -Dexec.args="query --index /path/to/index --query /path/to/new-docs --backend ENSEMBLE --top-k 10"
```

Options: `--backend TFIDF|SBERT|ENSEMBLE` (BM25 / vector / RRF of both), `--top-k`, and `--no-embeddings` on `index` to build a lexical-only index without the model. Ensemble scores are RRF rank-fusion values (small, rank-based), not `[0,1]` similarities — the *ranking* is the signal.

Scale notes: doc-level embeddings (one vector per document) keep the vector index tractable at 100k–1M+ docs; retrieval is two-stage (cheap BM25/ANN candidates). Indexing currently reads a directory batch into memory — for very large archives, add in batches (each `index` call appends). A future refinement is re-ranking the top candidates with the full sentence-alignment scorer.

## Turnitin-style originality reports

When querying the corpus, add `--html-report <dir>` to produce a self-contained, **Turnitin-style HTML originality report** per query document:

```bash
mvn -pl text-semantic-engine exec:java -Dexec.mainClass=de.jplag.text.semantic.CorpusCli \
  -Dexec.args="query --index /path/to/index --query /path/to/new-docs --top-k 3 --html-report /path/to/reports --sentence-threshold 0.7"
```

Each report shows:
- an **overall similarity score** and, separately, an **unattributed score** — the share of matched words that are *not* quoted or cited, i.e. the actual plagiarism concern,
- a **category breakdown** — each match is classified by how much of the *literal wording* it shares with its source: **Copy-paste** (near-identical), **Lightly edited**, or **Paraphrase** (same meaning, different words),
- an **attribution split** — matched sentences that are quoted or cited (marked ✓, de-emphasized) vs unattributed,
- a **ranked list of sources** with their contribution percentage,
- the **query text with matched sentences highlighted** inline, **colour-coded by category**; hovering a highlight shows the category, attribution status, matched source, and similarity.

Two orthogonal signals drive it. The **category** combines high *semantic* similarity (why it matched) with *lexical* overlap (Jaccard of the sentences' words), separating copied wording from genuine rewording. The **attribution** status ([CitationDetector](src/main/java/de/jplag/text/semantic/CitationDetector.java)) checks each matched query sentence for quotation marks or a citation — `(Author, 2020)`, `[3]`, a URL, or a DOI — so that acknowledged reuse is separated from unacknowledged reuse. Example: a student essay reusing three source sentences (one cited, one quoted, one bare) scores 74% overall but only **20% unattributed**.

Caveat: attribution detection is a lightweight text heuristic. It cannot see footnote superscripts (lost in PDF extraction), does not verify a citation actually matches the reused source, and treats any quotation marks as a quote — so dialogue quotes can read as attribution. Treat the unattributed figure as a strong signal, not a verdict.

It works at the **sentence** level via SBERT alignment (`--sentence-threshold` controls the cutoff), so it highlights *paraphrased* sentences, not only verbatim copies. Each query sentence is attributed to its single best-matching source, so identical sources are not double-counted. Requires the SBERT model (downloaded on first use); the report is a standalone `.html` file you open in any browser.

**By default, quoted/cited matches are hidden and excluded from the score** — acknowledged reuse is not plagiarism, so highlighting it would read as a false flag. The report highlights only the unattributed concerns, with a legend note of how much quoted/cited material was hidden (like Turnitin's "exclude quotes and bibliography"). Pass **`--show-attributed`** if you want to display them too (de-emphasized, marked ✓) for review.

Example (essay3, a paraphrase of essay1, cross-referenced against the archive): 54% similarity, with 42% attributed to `essay1_original`.

## Authorship verification (ghostwriting / contract cheating)

A different kind of check: not *did they reuse a source*, but *did they write it*. Content can be entirely original — so the plagiarism detectors find nothing — yet be written by someone else. Authorship verification compares **writing style**, not content.

It uses **Burrows's Delta** ([StylometryAnalyzer](src/main/java/de/jplag/text/semantic/StylometryAnalyzer.java)): each candidate author and the query document become a z-scored vector of the corpus's most-frequent-word relative frequencies (dominated by topic-independent function words like *the*, *however*, *just*, which authors use consistently and unconsciously). The candidate with the smallest Delta is the closest stylistic match; if that is not the claimed author, the style is inconsistent with them.

```bash
mvn -pl text-semantic-engine exec:java -Dexec.mainClass=de.jplag.text.semantic.AuthorshipCli \
  -Dexec.args="--known authors --query submissions --claimed alice"
```

`authors/` has one sub-directory per candidate author (their known prior documents); each query document is ranked against them. With `--claimed`, a submission whose closest style is *not* the claimed author is flagged as possible ghostwriting. Example: a submission claimed by "alice" but written in "bob's" style — `WARNING: style is closest to 'bob', not the claimed author 'alice'`.

Caveats: stylometry needs a reasonable amount of known text per author (a few hundred words minimum) and at least two candidates; it is a **triage signal**, not proof — genre, co-authoring, heavy editing, and translation all shift style. It answers "does this match the claimed author's style", not "who wrote it" in the open world.

## Using it as a library

```java
SemanticEngineConfiguration configuration = SemanticEngineConfiguration.builder()
        .similarityThreshold(0.5)
        .backend(SemanticEngineConfiguration.Backend.SBERT)   // or TFIDF (default)
        .expandSynonyms(true)   // .lemmatize(...), .removeStopwords(...), .fileExtensions(...)
        .build();

List<AnalyzedSubmission> submissions = new SubmissionReader(configuration).readSubmissions(rootDirectory);
SimilarityBackend backend = configuration.createBackend();
List<SubmissionPairSimilarity> results = backend.compare(submissions);
```

## Limitations

- **Lexical, not deeply semantic.** TF-IDF over normalized terms catches reordering, synonyms, and inflection, but two passages that share meaning without sharing (normalizable) vocabulary will not match. The neural/embedding upgrade path slots in at the [`TfIdfVectorizer`](src/main/java/de/jplag/text/semantic/TfIdfVectorizer.java) / [`SparseVector`](src/main/java/de/jplag/text/semantic/SparseVector.java) seam.
- **English** lemmatization/synonyms (WordNet). Disable them for other languages.
- `--expand-synonyms` has no word-sense disambiguation, so it raises recall at some cost to precision.
- All-pairs comparison is `O(n²)` in the number of submissions (fine for typical class sizes; not tuned for very large corpora).
- Results are **not** shown in the JPlag report viewer — this engine produces its own JSON/CSV.
