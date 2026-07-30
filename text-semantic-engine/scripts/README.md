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

`SENTENCE_THRESHOLD` is not the last word on it, though: each report carries its
matches down to 0.10 below the value it was generated with, and its controls let
a reader move the threshold and switch whole match types off, with every
percentage following. So an assignment where "paraphrase" fires on the entire
cohort — one set text, one right answer — can be read without it, and without
re-running anything. See
[Reading a report down](../README.md#reading-a-report-down).

Read a match as evidence only where the *wording*, not merely the subject, is
shared: the report's category (copy-paste / lightly edited / paraphrase) comes
from literal word overlap and is the signal to weigh. Note also that a cohort's
shared assignment cover sheet matches near-perfectly and will dominate a report
unless it is stripped from the documents beforehand.

`MINIMUM_WORD_OVERLAP=<0-1>` (default `0`) turns that reading into a filter:
a match must share that share of its wording as well as clearing the sentence
threshold. Leave it at `0` against the cohort's own submissions — two students
share wording only by copying. Raise it against published or reference material,
where they do not: measured against six Wikipedia articles, a 40-submission
enzyme-kinetics cohort produced 32 matches at `0`, every one of them a standard
definition stated correctly ("Km is the substrate concentration at which the
velocity is half of Vmax") and none of them copied. All 32 were classified
*paraphrase*; none were *copy-paste*. `0.4` removes them.

## Checking against the sources a cohort cites

The papers in a cohort's reference lists are the papers its students read, which
is where copied text comes from. That makes the bibliography a better source
corpus than a topic search, which returns the documents most likely to be *about*
the same subject — exactly the property that produces false positives. It is also
the only web-facing step that keeps student work inside the building: what leaves
is a DOI, never a submission.

```bash
# after a normal run, mine its reports for cited works
OPENALEX_MAILTO=you@example.ac.uk \
  scripts/bibliography-corpus.py ./results/reports ./cited-corpus

scripts/build-database.sh ./cited-corpus ./cited-index
MINIMUM_WORD_OVERLAP=0.4 SOURCE_TEXT=EXCERPT \
  scripts/run-plagiarism.sh ./submissions ./cited-index ./cited-results
```

`SOURCE_TEXT` decides how much of a matched source each report reprints: `FULL`
(the default) the whole thing, `EXCERPT` only the matched passages and a sentence
of context either side, `NONE` nothing at all. Full text is right for a cohort's
own submissions and is what makes a match checkable at a glance; against
published or licensed material it reproduces far more of the source than the
finding needs, which is a licence question as much as a size one. It is the size
control as well — a 45-submission coursework produced 7.2 MB of reports at `FULL`
and 2.3 MB at `EXCERPT`.

Two caveats worth knowing before reading the output. The corpus is built from
abstracts, so it tests whether a submission reproduces a paper's *abstract*, not
its body — and it only covers sources a student was willing to name. And leave
`KEEP_TITLES` off: a reference-list entry is a title, so a corpus containing
titles matches every correctly formatted bibliography at 100% and reads like mass
plagiarism.

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
against each other.

Documents are named after the path they came from, so a report is titled with
the facts a reader needs rather than with export ids:
`2025_6/AH1001/865937/240026012-MTP-4973291.pdf` becomes
`2025_6-AH1001-MTP-240026012`, with a `-warned` suffix where it applies. Set
`NAME_PATTERN=''` and `NAME_TEMPLATE=''` to go back to naming by file name, or
override the pair for a differently organized corpus (see
[the engine README](../README.md#naming-documents)). `AUTHOR_PATTERN` (default
`'([0-9]{8,})'`, the one long run of digits in the name) identifies each
document's author so that a student's own resubmission is not reported as
plagiarism — if you change the naming, check that it still finds the student id.

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
