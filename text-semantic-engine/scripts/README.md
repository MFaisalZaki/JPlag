# Plagiarism scripts

Three shell scripts that wrap the semantic text engine into a simple
build → index → check workflow. The engine walks each given directory
**recursively** and keeps only the extensions listed in [`common.sh`](common.sh)
(`ACCEPTED_EXTENSIONS`, default: `txt asc tex md rtf csv wiki json yaml yml xml pdf`),
which the scripts pass through as `--extensions`. Every accepted file — however
deeply nested — becomes **one document**; its path is encoded into the document
name (`sub/dir/file.txt` → `sub__dir__file`).

## 1. Build

```bash
scripts/build.sh
```

Compiles the engine and its dependencies and records the runtime classpath at
`target/runtime-classpath.txt`. Run once, and again after code changes.

## 2. Build the database (index)

```bash
scripts/build-database.sh <docs-dir> <index-dir>
```

Recursively indexes every accepted file under `<docs-dir>` into a searchable
corpus at `<index-dir>`. Re-running appends (the index is incremental).

Options (env vars): `AUTHOR=<name>` tags every document with one author;
`AUTHOR_PATTERN=<regex>` derives each file's author from its file name instead
(first capture group, e.g. `'^([0-9]+)-'` for `<studentid>-essay.pdf`, falling
back to `AUTHOR` where it does not match) — either enables self-plagiarism
handling at query time; `NO_EMBEDDINGS=1` builds a lexical-only index (no model
download).

## 3. Run the plagiarism check

```bash
scripts/run-plagiarism.sh <query-dir> <index-dir> <results-dir>
```

Recursively checks every accepted file under `<query-dir>` against the index and
writes to `<results-dir>`:

- `reports/<document>.html` — a Turnitin-style originality report per file
  (highlighted matches, ranked sources, quoted-vs-unattributed score),
- `matches.txt` — the ranked source matches per document.

Options (env vars): `BACKEND=TFIDF|SBERT|ENSEMBLE` (default `ENSEMBLE`),
`TOP_K=<n|all>` (default `all` — see below), `SENTENCE_THRESHOLD=<0-1>` (default 0.85),
`AUTHOR=<name>` / `AUTHOR_PATTERN=<regex>` (the query documents' author, one
for all or derived per file as above), `SAME_AUTHOR=exclude|flag` (default
`exclude`: matches to the query author's own indexed work are dropped
entirely, so a resubmission of the same document is not reported at all;
`flag` keeps them, marked as self-plagiarism).

### How many documents each query is compared against

`TOP_K` decides the pool of archived documents a query is compared against
sentence by sentence. A document outside that pool cannot be matched however
similar it is, so `all` (the default) compares against the whole index and
retrieval only *orders* the results rather than deciding what gets looked at.

Set a number only for a corpus too large to compare in full. Cost is roughly
linear in the documents compared: each one's sentences are embedded once per run
(cached across queries) and then compared against every query sentence. Note
that a wider pool also gives a fixed `SENTENCE_THRESHOLD` more chances to be
crossed by coincidence, so on a large corpus expect to raise the threshold or
enable the filters below.

### Keeping topical similarity out of the report

Sentence embeddings score any two sentences on the same subject highly whether
or not either was copied, so on a set of documents answering one prompt raw
similarity reports the shared topic rather than reuse. **Every match is
reported by default**; three optional filters narrow that down to passages with
positive evidence of reuse:

- `SENTENCE_THRESHOLD=<0-1>` (default `0.85`) — the cosine cutoff, always
  applied. Because the engine takes the best match over every sentence of every
  candidate source, the cutoff applies to a maximum over hundreds of
  comparisons; values near `0.7` are crossed by chance alone on same-topic
  prose.
- `MIN_LEXICAL_OVERLAP=<0-1>` (default `0`, i.e. off) — distinctive wording a
  match must share with its source, weighted by how rare each word is across
  the documents compared, so a cohort's own topic vocabulary counts for nothing
  while rare wording counts for a lot. `0.10` drops matches that share only
  their subject.
- `MAX_SOURCE_FRACTION=<0-1>` (default `1`, i.e. off) — a passage present in
  more than this share of the candidate sources is shared material (a common
  citation, a stock definition) rather than something reused from any one of
  them. `0.75` suits a cohort answering one prompt.
- `BOILERPLATE=include|exclude` (default `include`) — `exclude` drops
  assignment cover sheets and academic-integrity declarations from matching and
  from the word total. Worth setting for a cohort that shares a cover sheet:
  identical front matter matches near-perfectly and otherwise outranks every
  genuine match.

Filtered passages are left as plain, unhighlighted text rather than annotated,
so a report does not distinguish "filtered" from "never similar". Measured on a
26-submission single-prompt cohort, the defaults report 31 matches (17 of them
the shared cover sheet); enabling all three filters leaves 3.

## One-shot: check a self-contained dataset

```bash
scripts/check-dataset.sh <dataset-dir> <results-dir>
```

For a dataset that is its own corpus: the files directly inside
`<dataset-dir>` are indexed as the source pool, then **every** accepted file
(top-level and sub-directories alike) is checked against it — so a layout of
originals at the top level plus a sub-directory of suspect documents needs no
separate index step. A file is never matched against itself. Builds the engine
on first use, creates a fresh index per run, and writes the same outputs as
`run-plagiarism.sh` plus `summary.txt` — one line per document with its
matched percentage and top source, most suspicious first.

Options (env vars): `BACKEND`, `TOP_K`, `SENTENCE_THRESHOLD`,
`MIN_LEXICAL_OVERLAP`, `MAX_SOURCE_FRACTION`, `BOILERPLATE` as above;
`AUTHOR_PATTERN=<regex>` derives each file's author from its file name (first
capture group), and `SAME_AUTHOR=exclude|flag` (default `exclude` here) then
controls whether a file's matches to the **same author's** other files — e.g. a
resubmission of the same essay under a new name — are dropped entirely or shown
as self-reuse; `NO_EMBEDDINGS=1` for a lexical-only offline run (no HTML
reports; `summary.txt` then ranks by raw BM25 retrieval score).

## Example

```bash
scripts/build.sh
scripts/build-database.sh ./past-submissions ./corpus-index
scripts/run-plagiarism.sh ./new-submissions ./corpus-index ./results
open ./results/reports/*.html
```

> Note: the `ENSEMBLE`/`SBERT` backends and the HTML reports use a local SBERT
> model (all-MiniLM-L6-v2), downloaded automatically on first use. Use
> `NO_EMBEDDINGS=1` at index time + `BACKEND=TFIDF` to stay fully offline
> (lexical only; no HTML report).
