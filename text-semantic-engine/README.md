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

The **SBERT** backend splits each document into sentences (CoreNLP `ssplit`), embeds each sentence locally, and scores a pair by *soft passage alignment* — each sentence's best-matching counterpart in the other document, averaged symmetrically (which, unlike mean-pooling, does not wash out on long documents). The model and PyTorch native runtime are fetched automatically by [Deep Java Library](https://djl.ai/) the first time you run it. SBERT produces document-level scores rather than token matches, so it does not emit shared-term explanations.

Which to use: **TFIDF** is the strong, cheap default and wins when paraphrases keep vocabulary. Reach for **SBERT** when you expect genuine rewording where lexical overlap collapses. Example:

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
