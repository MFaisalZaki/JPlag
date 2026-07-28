# Plagiarism scripts

Shell scripts that wrap the semantic text engine into a simple
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
crossed by coincidence, so on a large corpus expect to raise it.

### Keeping topical similarity out of the report

Sentence embeddings score any two sentences on the same subject highly whether
or not either was copied, so on a set of documents answering one prompt raw
similarity reports the shared topic rather than reuse. The one control is
`SENTENCE_THRESHOLD=<0-1>` (default `0.85`). Because the engine takes the best
match over every sentence of every candidate source, that cutoff applies to a
maximum over hundreds of comparisons — which is why it has to sit high: values
near `0.7` are crossed by chance alone on same-topic prose. Measured on a
26-submission single-prompt cohort, `0.70` reports 173 matches and `0.85`
reports 31.

Read a match as evidence only where the *wording*, not merely the subject, is
shared: the report's category (copy-paste / lightly edited / paraphrase) comes
from literal word overlap and is the signal to weigh. Note also that a cohort's
shared assignment cover sheet matches near-perfectly and will dominate a report
unless it is stripped from the documents beforehand.

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

Options (env vars): `BACKEND`, `TOP_K`, `SENTENCE_THRESHOLD` as above;
`AUTHOR_PATTERN=<regex>` derives each file's author from its file name (first
capture group), and `SAME_AUTHOR=exclude|flag` (default `exclude` here) then
controls whether a file's matches to the **same author's** other files — e.g. a
resubmission of the same essay under a new name — are dropped entirely or shown
as self-reuse; `NO_EMBEDDINGS=1` for a lexical-only offline run (no HTML
reports; `summary.txt` then ranks by raw BM25 retrieval score).

## Batch: a whole term of modules and courseworks

```bash
scripts/run-all-courseworks.sh <dataset-root> <output-root>
```

For a tree of `<module>/<coursework>/` directories, each holding its submissions
plus a `warned/` sub-directory. Every coursework is checked **entirely on its
own** — its own index, its own reports, no cross-module comparison — and the
output mirrors the input layout:

```
<output-root>/
  <module>/<coursework>/
    reports/<document>.html   one originality report per submission
    matches.txt               ranked source matches per submission
    summary.txt               each submission's matched % and top source
    run.log                   full engine output for this coursework
    index/                    the coursework's index (KEEP_INDEX=0 to drop it)
  stats.csv                   one row per coursework
  stats.txt                   the same as a table, with per-module and overall averages
  run.log
```

Everything under a coursework is both indexed **and** checked, `warned/`
included, so warned submissions are compared against the regular ones *and*
against each other. `AUTHOR_PATTERN` (default `'^([0-9]+)-'`, matching
`<studentid>-<assignment>-<submissionid>.pdf`) identifies each file's author so
that a student's own resubmission is not reported as plagiarism.

`stats.txt` records wall time and peak memory per coursework, then averages
them per module and overall — peak RSS comes from `/usr/bin/time -l`, which
reports the JVM's usage rather than the wrapper shell's.

Options (env vars): `BACKEND`, `TOP_K`, `SENTENCE_THRESHOLD`, `AUTHOR_PATTERN`,
`SAME_AUTHOR` as above, plus `KEEP_INDEX=0` to delete each index after use,
`RESUME=1` to skip courseworks already recorded in `stats.csv` (their statistics
are preserved, so an interrupted run can be continued), and `ONLY=<pattern>` to
restrict the run, e.g. `ONLY='SD2005/*'`.

## Container

The whole workflow — engine, Java runtime and SBERT model — packs into a single
Apptainer image whose entry point is `run-all-courseworks.sh`, so a cluster run
needs nothing installed on the host:

```bash
text-semantic-engine/apptainer/build-image.sh          # once, needs network
apptainer run plagiarism-check.sif ./2025_6 ./results  # offline from here on
```

See [`../apptainer/README.md`](../apptainer/README.md).

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
