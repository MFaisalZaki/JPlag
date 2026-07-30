#!/usr/bin/env bash
#
# check-dataset.sh — plagiarism-check a dataset laid out as one directory of
# original documents plus sub-directories of documents to test:
#
#     <dataset-dir>/
#       report1.pdf            <- top-level files: indexed as the source corpus
#       report2.pdf
#       …
#       plagiarised/           <- any sub-directory: checked against the corpus
#         copy1.pdf
#
# The TOP-LEVEL files are indexed as the corpus; then EVERY accepted file in
# the dataset (top-level and sub-directories alike) is checked against it, so
# each file gets its own Turnitin-style HTML originality report. A top-level
# file is never matched against itself (the engine excludes a query's own
# index id), so its report only shows overlap with OTHER originals.
#
# Usage:
#   scripts/check-dataset.sh <dataset-dir> <results-dir>
#
# Output (in <results-dir>):
#   reports/<document>.html   one originality report per file
#   matches.txt               ranked source matches per document
#   summary.txt               each document's top match, highest score first
#
# Options (environment variables):
#   BACKEND=<TFIDF|SBERT|ENSEMBLE>  retrieval signal (default: ENSEMBLE).
#   TOP_K=<n|all>                   archived documents to compare each query against, most
#                                   similar first. 'all' (default) compares against every
#                                   indexed document, so nothing is missed because retrieval
#                                   ranked it low. Set a number for a very large corpus.
#   SENTENCE_THRESHOLD=<0-1>        sentence match cutoff for the report (default: 0.85).
#                                   Embeddings rate any two sentences on the same topic
#                                   highly, so lower values report shared subject matter
#                                   rather than reuse.
#   NO_EMBEDDINGS=1                 lexical-only run (forces BACKEND=TFIDF, no HTML reports).
#   AUTHOR_PATTERN=<regex>          derive each file's author from its file name (first capture
#                                   group), e.g. '^([0-9]+)-' for '<studentid>-essay.pdf' names.
#   SOURCE_TEXT=<FULL|EXCERPT|NONE> how much of a matched source each report reproduces
#                                   (default: FULL). EXCERPT keeps only the matched passages
#                                   and a sentence of context either side; NONE names the
#                                   sources without reproducing them — for corpora of
#                                   published or licensed material.
#   SAME_AUTHOR=<exclude|flag>      with AUTHOR_PATTERN: 'exclude' (default) never matches a file
#                                   against the same author's other files (so a resubmission of
#                                   the same essay is not reported as plagiarism); 'flag' keeps
#                                   such matches but renders them as self-reuse in the report.
#
# NAME_PATTERN / NAME_TEMPLATE (naming documents after their path, as
# run-all-courseworks.sh does) are deliberately not supported here: this script
# stages the corpus through a temporary directory, so the path a document is
# indexed under is not the path it is queried under, and the two would end up
# under different names. Documents keep their file names.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

usage() { sed -n '2,55p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then usage; exit 0; fi
[[ $# -eq 2 ]] || { usage; die "expected 2 arguments, got $#."; }

DATASET_DIR="$1"
RESULTS_DIR="$2"
BACKEND="${BACKEND:-ENSEMBLE}"
TOP_K="${TOP_K:-all}"
# The engine takes 0 to mean "the whole index"; 'all' is just the readable spelling.
if [[ "$TOP_K" == "all" ]]; then
  TOP_K_ARG=0
else
  [[ "$TOP_K" =~ ^[0-9]+$ ]] || die "TOP_K must be a number or 'all', got '$TOP_K'."
  TOP_K_ARG="$TOP_K"
fi
SENTENCE_THRESHOLD="${SENTENCE_THRESHOLD:-0.85}"
AUTHOR_PATTERN="${AUTHOR_PATTERN:-}"
SOURCE_TEXT="${SOURCE_TEXT:-FULL}"
SAME_AUTHOR="${SAME_AUTHOR:-exclude}"
[[ "$SAME_AUTHOR" == "exclude" || "$SAME_AUTHOR" == "flag" ]] || die "SAME_AUTHOR must be 'exclude' or 'flag', got '$SAME_AUTHOR'."

require_java
[[ -d "$DATASET_DIR" ]] || die "dataset directory not found: $DATASET_DIR"

# Build the engine on first use so this stays a one-command workflow.
if [[ ! -f "$CLASSPATH_FILE" ]]; then
  echo ">> Engine not built yet — running scripts/build.sh first…"
  "$SCRIPT_DIR/build.sh"
fi
load_classpath

HTML_REPORTS=1
if [[ -n "${NO_EMBEDDINGS:-}" ]]; then
  [[ "$BACKEND" == "TFIDF" ]] || echo ">> NO_EMBEDDINGS=1: forcing BACKEND=TFIDF (was $BACKEND); HTML reports are skipped."
  BACKEND=TFIDF
  HTML_REPORTS=0
fi

# List the accepted files directly inside the dataset directory (the corpus).
list_top_level_accepted() {
  local ext find_expr=()
  for ext in "${ACCEPTED_EXTENSIONS[@]}"; do
    find_expr+=(-iname "*.${ext}" -o)
  done
  unset 'find_expr[${#find_expr[@]}-1]'
  find "$DATASET_DIR" -maxdepth 1 -type f \( "${find_expr[@]}" \) -print0
}

print_accepted_extensions

# The engine indexes a directory recursively, so the corpus (top-level files
# only) is staged into a temporary directory. Staged names keep their relative
# path (just the basename), so a top-level file gets the SAME document id when
# indexed and when queried — which is what makes the self-exclusion work.
STAGING_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGING_DIR"' EXIT

CORPUS_COUNT=0
while IFS= read -r -d '' file; do
  cp "$file" "$STAGING_DIR/"
  CORPUS_COUNT=$((CORPUS_COUNT + 1))
done < <(list_top_level_accepted)
[[ "$CORPUS_COUNT" -gt 0 ]] || die "no accepted files found at the top level of '$DATASET_DIR' — nothing to use as the corpus."

TOTAL_COUNT="$(count_accepted_files "$DATASET_DIR")"
echo ">> Corpus: $CORPUS_COUNT top-level file(s); checking $TOTAL_COUNT file(s) in total (sub-directories included)."

mkdir -p "$RESULTS_DIR"
INDEX_DIR="$RESULTS_DIR/index"
REPORTS_DIR="$RESULTS_DIR/reports"
SUMMARY_FILE="$RESULTS_DIR/matches.txt"
TOP_MATCH_FILE="$RESULTS_DIR/summary.txt"
# Fresh run: a stale index would keep documents whose files were since removed.
rm -rf "$INDEX_DIR" "$REPORTS_DIR"

echo ">> Indexing the corpus…"
INDEX_ARGS=(index --index "$INDEX_DIR" --extensions "$(extensions_csv)")
[[ -n "${NO_EMBEDDINGS:-}" ]] && INDEX_ARGS+=(--no-embeddings)
[[ -n "$AUTHOR_PATTERN" ]] && INDEX_ARGS+=(--author-pattern "$AUTHOR_PATTERN")
INDEX_ARGS+=("$STAGING_DIR")
java -cp "$JPLAG_CP" "$MAIN_CLASS" "${INDEX_ARGS[@]}"

echo ">> Running plagiarism check (backend=$BACKEND, top-k=$TOP_K, sentence-threshold=$SENTENCE_THRESHOLD)…"
[[ -n "$AUTHOR_PATTERN" ]] && echo ">> Authors derived via AUTHOR_PATTERN='$AUTHOR_PATTERN'; same-author matches: $SAME_AUTHOR."
QUERY_ARGS=(query --index "$INDEX_DIR" --query "$DATASET_DIR"
            --backend "$BACKEND" --top-k "$TOP_K_ARG"
            --sentence-threshold "$SENTENCE_THRESHOLD"
            --extensions "$(extensions_csv)"
            --source-text "$SOURCE_TEXT")
if [[ -n "$AUTHOR_PATTERN" ]]; then
  QUERY_ARGS+=(--author-pattern "$AUTHOR_PATTERN")
  [[ "$SAME_AUTHOR" == "flag" ]] && QUERY_ARGS+=(--no-exclude-same-author)
fi
[[ "$HTML_REPORTS" -eq 1 ]] && QUERY_ARGS+=(--html-report "$REPORTS_DIR")
java -cp "$JPLAG_CP" "$MAIN_CLASS" "${QUERY_ARGS[@]}" | tee "$SUMMARY_FILE"

# Condense the results into one line per document, most suspicious first
# (summary helpers live in common.sh, shared with run-all-courseworks.sh).
if [[ "$HTML_REPORTS" -eq 1 ]]; then
  write_summary_table "$REPORTS_DIR" "$SUMMARY_FILE" "$TOP_MATCH_FILE"
else
  write_summary_table "" "$SUMMARY_FILE" "$TOP_MATCH_FILE"
fi

echo
echo "Results saved to: $(abspath "$RESULTS_DIR")"
echo "  at-a-glance    : $TOP_MATCH_FILE  (each document's top match, ranked)"
echo "  ranked matches : $SUMMARY_FILE"
if [[ "$HTML_REPORTS" -eq 1 ]]; then
  echo "  HTML reports   : $REPORTS_DIR/  (one <document>.html per checked file)"
fi
