# JPlag Semantic Text Engine

A **standalone** plagiarism engine for natural-language text. It maintains a persistent index of an archive of documents and cross-references new submissions against it, producing a **Turnitin-style HTML originality report** per submission.

Unlike JPlag's token-based Greedy String Tiling (GST), matching is **semantic**: sentences are compared by SBERT embedding cosine, so it flags passages that were *reworded* rather than copied. It **does not modify the JPlag comparison core** — it reuses the text module for tokenization and normalization and runs as its own tool.

| Approach | Catches | Misses | Where |
|---|---|---|---|
| **Core GST** (`text` module) | (near-)verbatim reuse | any synonym/inflection change | [languages/text](../languages/text/README.md) |
| **Text module + normalization flags** | + synonyms, inflections (order-preserving) | reordering, restructuring | [languages/text](../languages/text/README.md) |
| **This engine** | + reordering, restructuring, and rewrites that share meaning without sharing vocabulary | reuse of a source that is not in the index | here |

Everything stays **on your machine** — no external API calls. The SBERT model (all-MiniLM-L6-v2) and PyTorch runtime are downloaded once by [Deep Java Library](https://djl.ai/) on first use.

## How it works

```
archive ──▶ index once ──▶ ┌ BM25 over normalized terms ┐
                           └ HNSW over SBERT embeddings ┘
                                        │
new documents ──▶ retrieve candidates ──┴─▶ align sentence by sentence ──▶ HTML report
```

1. **Ingest** — every accepted file below the given directory is one document, named by its path relative to the root (`sub/dir/file.txt` → `sub__dir__file`). `.pdf` files are text-extracted directly with Apache PDFBox; no `pdftotext` step needed.
2. **Normalize** — delegated to the text module's `ParserAdapter`: WordNet lemmatization, stop-word removal, and synonym canonicalization (fixed on, since indexing and querying have to agree).
3. **Index** — [Apache Lucene](https://lucene.apache.org/): a BM25 inverted index over the normalized terms plus an HNSW dense-vector field over the document's SBERT embedding. Incremental — documents are added or updated by id.
4. **Query** — retrieve candidates by BM25, by vector nearest-neighbour, or by both fused with **reciprocal-rank fusion** (which combines the two rankings without reconciling their different score scales), then align every query sentence to its best match among the candidates.

## Usage

```bash
# Build or extend the index (parses + embeds; downloads the model on first use).
mvn -pl text-semantic-engine exec:java -Dexec.args="index --index /path/to/index /path/to/archive"

# Cross-reference new documents against the archive.
mvn -pl text-semantic-engine exec:java \
  -Dexec.args="query --index /path/to/index --query /path/to/new-docs --html-report /path/to/reports"
```

For a ready-made build → index → check workflow, use the wrapper scripts in [scripts/](scripts/) instead.

### `index` options

| Option | Default | Description |
|---|---|---|
| `<directory>` | (required) | Directory of documents to add; every accepted file below it is one document. |
| `--index <dir>` | (required) | Directory holding the Lucene index. |
| `--author <name>` | (none) | Author of these documents; enables self-plagiarism handling at query time. |
| `--author-pattern <regex>` | (none) | Derive each file's author from its file name (first capture group), e.g. `'^([0-9]+)-'` for `<studentid>-essay.pdf`. Unmatched files fall back to `--author`. |
| `--extensions <a,b,…>` | text module's + `.pdf` | Comma-separated file extensions to include (leading dot optional). |
| `--name-pattern <regex>` / `--name-template <template>` | (none) | Name each document from its path rather than by its path — see [Naming documents](#naming-documents). |
| `--no-embeddings` | off | Build a lexical-only (BM25) index without downloading the model. |

### `query` options

| Option | Default | Description |
|---|---|---|
| `--index <dir>` | (required) | Directory holding the Lucene index. |
| `--query <dir>` | (required) | Directory of query documents; every accepted file below it is one. |
| `--backend <TFIDF\|SBERT\|ENSEMBLE>` | `ENSEMBLE` | Retrieval signal: BM25, vector, or RRF of both. |
| `--top-k <n>` | `0` (= all) | Archived documents to compare each query against. `0` compares against the whole index, so nothing is missed because retrieval ranked it low; set a limit only for a corpus too large to compare in full. |
| `--html-report <dir>` | (none) | Write a Turnitin-style HTML originality report per query document. Requires SBERT. |
| `--sentence-threshold <0-1>` | `0.85` | Sentence cosine similarity to count as a match. The report can be re-thresholded by its reader afterwards, within 0.10 below this and up to 1.0 — see [Reading a report down](#reading-a-report-down). |
| `--minimum-word-overlap <0-1>` | `0` | Literal word overlap (Jaccard) a match must reach on top of the sentence threshold. Leave at `0` when the index holds the cohort's own submissions; raise it (`0.4` lightly edited, `0.8` near-verbatim) against published or reference material, whose standard sentences match everyone who states them correctly. |
| `--show-attributed` | off | Also highlight quoted/cited matches (de-emphasized). |
| `--author <name>` / `--author-pattern <regex>` | (none) | The query documents' author, as at index time. |
| `--[no-]exclude-same-author` | on | Never match a document against its own author's other indexed work (e.g. a resubmission). `--no-exclude-same-author` keeps such matches, flagged as self-plagiarism. |
| `--extensions <a,b,…>` | text module's + `.pdf` | Comma-separated file extensions to include. |
| `--name-pattern <regex>` / `--name-template <template>` | (none) | As at index time, and they have to agree — see [Naming documents](#naming-documents). |

Ensemble scores are RRF rank-fusion values (small, rank-based), not `[0,1]` similarities — the *ranking* is the signal, which is also why no score cutoff is applied at retrieval.

### Naming documents

A document's name is its identity: the index key, the title of its report, and the name of the report file. By default it is the document's path relative to the directory you indexed, with the directory separators encoded — so a submission exported as `2025_6/AH1001/865937/240026012-MTP-4973291.pdf` and indexed at its coursework directory is called `240026012-MTP-4973291`. That is a student id, an assignment abbreviation and a submission id, of which the middle one is the only part a reader gets anything from.

`--name-pattern` and `--name-template` name it from the path instead. The pattern is a regex matched against the document's **full path** (with `/` separators on every platform); the template puts its groups back in a readable order:

```bash
--name-pattern '(?<ayr>[^/]+)/(?<module>[^/]+)/[^/]+/(?<warned>warned)?/?(?<student>[0-9]+)-(?<assignment>.+?)-[0-9]+\.[^./]+$' \
--name-template '{ayr}-{module}-{assignment}-{student}-{warned}'
```

names that same file `2025_6-AH1001-MTP-240026012`. Groups can be referred to by name (`{module}`) or by number (`{1}`). Anchor the pattern at the end with `$`, since the directories above a corpus differ from machine to machine. A group the path does not fill — `warned` here, which only some submissions sit under — contributes nothing and the separator that would have joined it is tidied away, so an optional part shows up only when it applies. Files the pattern does not match keep their default name, so a partly organized corpus still ingests in full.

Because the name is the index key, `index` and `query` have to be given the same pattern and template, and changing the scheme means re-indexing. [`run-all-courseworks.sh`](scripts/run-all-courseworks.sh) applies the naming above by default; note that the author regex has to suit the resulting names (it defaults there to `([0-9]{8,})`, the one long run of digits, rather than a leading id).

### Extending the index

The index is **incremental**: run `index` again against the same `--index` directory with new documents and they are appended; the existing index is never rewritten. Each document is keyed by its **id** (its path relative to the directory you point `index` at), so a new id is added and an existing id is updated in place — which is also how you refresh a changed document. Add in as many separate `index` calls as you like, which is useful for very large archives or for tagging batches with different `--author`.

## Originality reports

`--html-report <dir>` writes a self-contained HTML report per query document showing:

- an **overall similarity score** and, separately, an **unattributed score** — the share of matched words that are *not* quoted or cited, i.e. the actual plagiarism concern,
- a **category breakdown** — each match classified by how much of the *literal wording* it shares with its source: **Copy-paste** (near-identical), **Lightly edited**, or **Paraphrase** (same meaning, different words),
- a **ranked list of sources** with their contribution percentage,
- the **document itself, with matched sentences highlighted** inline and colour-coded by category; hovering shows the category, attribution status, source, and similarity, and clicking jumps to the matched passage in the source rendered below (and back).

The document is rendered **as it was submitted**: its paragraphs, line breaks, cover sheet, headings and the short lines that are never long enough to check are all there, with the matched sentences highlighted where they sit. What is *not* reproduced is the page itself — fonts, images, tables as tables — because the engine works on the extracted text, so a report is a faithful plain-text rendering of the document rather than a facsimile of it. Percentages are shares of that whole document's words, including the parts that were never candidates for a match.

### Reading a report down

A report is not fixed at the settings it was generated with. Its controls re-decide every match in the page:

- **click a type** in the legend — Copy-paste, Lightly edited, Paraphrase, self-reuse, quoted/cited — to take it out, and every percentage, the source list and the source passages follow;
- **move the threshold** to keep only closer matches, or to look a little harder;
- **narrow by how widely a passage is shared** — keep only what at most *n* of the sources carry.

This matters most where a category is true of a whole cohort and means nothing by it. On an assignment built around one set text — a History gobbet, a lab report with a fixed method — every good answer says much the same thing, so "paraphrase" can fire on every submission. A category that fires on everything is worse than no category at all, and the reader can now take it out and see what is left rather than discount it in their head or ask for the run to be repeated with different settings.

The sharing count is the sharper tool for that, because it separates the two cases directly: a passage one other student has is worth reading, a passage the whole cohort has is what the assignment asked for. On a nine-submission Art History cohort the passages four of the nine carried turned out to be the essay's own framing sentence ("In this essay, I will analyze Anguissola's *Self Portrait* from 1556") and the citation of the set reading — the assignment, not misconduct — and narrowing to "at most two sources" took the report from 5% to 2%. Each match's count is in its tooltip either way; the control only decides what you look at, and the report opens with all of it.

The report carries matches down to 0.10 below the threshold it was generated with, which is how far the slider reaches: turning a report *down* needs matches that were written into the file, whereas turning it *up* needs nothing extra. The percentages saved in the file are the ones the run produced — the controls change what you see, not what was recorded — so the batch scripts' summaries are unaffected, and a report read with scripting off is exactly the report as generated.

Two orthogonal signals drive it. The **category** combines high *semantic* similarity (why it matched) with *lexical* overlap (Jaccard of the sentences' words), separating copied wording from genuine rewording. The **attribution** status ([CitationDetector](src/main/java/de/jplag/text/semantic/CitationDetector.java)) checks each matched sentence for quotation marks or a citation — `(Author, 2020)`, `[3]`, a URL, or a DOI — so acknowledged reuse is separated from unacknowledged reuse.

**By default, quoted/cited matches are hidden and excluded from the score** — acknowledged reuse is not plagiarism, so highlighting it would read as a false flag (like Turnitin's "exclude quotes and bibliography"). Pass `--show-attributed` to display them too, de-emphasized and marked ✓.

Because matching is at the **sentence** level via SBERT alignment, paraphrased sentences are highlighted, not only verbatim copies. Each query sentence is attributed to its single best-matching source, so identical sources are not double-counted.

### Self-plagiarism

Tag indexed documents with an author to detect a student reusing their own prior work:

```bash
# Index each student's prior work under their name.
jplag-corpus index --index /path/to/index --author alice /path/to/alice-prior-docs

# Check a new submission.
jplag-corpus query --index /path/to/index --query submission --author alice --html-report reports
```

By default a match to the query author's own indexed work is **excluded entirely**, which is what you want for resubmissions of the same essay under a new id. Pass `--no-exclude-same-author` to keep them instead: they are then labelled `[SELF-PLAGIARISM]` in the console and marked ↺ in its own colour in the report, distinguishing self-reuse from ordinary plagiarism.

## Using it as a library

```java
List<AnalyzedSubmission> documents = new SubmissionReader(SubmissionReader.defaultFileExtensions())
        .readDocuments(archiveDirectory);

try (SbertEmbedder embedder = new SbertEmbedder()) {
    LuceneCorpusIndex index = new LuceneCorpusIndex(indexPath, embedder);
    index.index(documents, document -> Set.of());   // or the document's author(s)

    List<CorpusMatch> matches = index.query(query, Backend.ENSEMBLE, index.size(), Set.of());
    List<ArchivedDocument> sources = index.documents(matches.stream().map(CorpusMatch::documentId).toList());
    String html = new OriginalityReportGenerator(0.85, 0, embedder::embedSentencesWithText, true)
            .generate(query.name(), query.text(), sources, Set.of());
}
```

## Limitations

- **Similarity is not proof.** Sentence embeddings rate any two sentences on one subject highly whether or not either was copied, so on a set of documents answering one prompt some similarity is expected. That is why `--sentence-threshold` defaults to `0.85` rather than `0.70`; read a match as evidence only where the *wording*, not merely the subject, is shared — which is what the category breakdown reports.
- **Shared material matches near-perfectly, and is counted.** Every sentence is checked — a cohort's common cover sheet, academic-integrity declaration, assignment brief and shared reading list are identical by design, so they match near-perfectly and take a share of every score. Strip them from the documents beforehand if that matters for the run.
- **Attribution detection is a text heuristic.** It cannot see footnote superscripts (lost in PDF extraction), does not verify that a citation matches the reused source, and treats any quotation marks as a quote — so dialogue can read as attribution. Treat the unattributed figure as a strong signal, not a verdict.
- **English** lemmatization and synonyms (WordNet); synonym canonicalization has no word-sense disambiguation, so it raises recall at some cost to precision.
- Indexing reads a directory batch into memory — for very large archives, add in batches (each `index` call appends). Doc-level embeddings (one vector per document) keep the vector index tractable at 100k–1M+ docs.
