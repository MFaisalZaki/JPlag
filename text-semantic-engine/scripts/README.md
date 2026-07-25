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

Options (env vars): `AUTHOR=<name>` tags documents for self-plagiarism
detection; `NO_EMBEDDINGS=1` builds a lexical-only index (no model download).

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
`TOP_K=<n>` (default 5), `SENTENCE_THRESHOLD=<0-1>` (default 0.7),
`AUTHOR=<name>` (flag self-plagiarism).

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
