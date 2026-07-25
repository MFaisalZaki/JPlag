#!/usr/bin/env bash
#
# run-plagiarism.sh — check a directory of documents against a previously built
# corpus index and save the results.
#
# The engine searches <query-dir> RECURSIVELY and checks every accepted file
# (see common.sh) as its own document. For each one you get a Turnitin-style HTML
# originality report (matched passages highlighted, ranked sources, quoted-vs-
# unattributed score). A plain-text ranked-match summary is saved too.
#
# Usage:
#   scripts/run-plagiarism.sh <query-dir> <index-dir> <results-dir>
#
# Options (environment variables):
#   BACKEND=<TFIDF|SBERT|ENSEMBLE>  retrieval signal (default: ENSEMBLE).
#   TOP_K=<n>                       sources to consider per document (default: 5).
#   SENTENCE_THRESHOLD=<0-1>        sentence match cutoff for the report (default: 0.7).
#   AUTHOR=<name>                   flag reuse of this author's own indexed work
#                                   as self-plagiarism.
#
# Examples:
#   scripts/run-plagiarism.sh ./new-submissions ./corpus-index ./results
#   BACKEND=SBERT TOP_K=10 scripts/run-plagiarism.sh ./essays ./corpus-index ./out
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

usage() { sed -n '2,27p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then usage; exit 0; fi
[[ $# -eq 3 ]] || { usage; die "expected 3 arguments, got $#."; }

QUERY_DIR="$1"
INDEX_DIR="$2"
RESULTS_DIR="$3"
BACKEND="${BACKEND:-ENSEMBLE}"
TOP_K="${TOP_K:-5}"
SENTENCE_THRESHOLD="${SENTENCE_THRESHOLD:-0.7}"
AUTHOR="${AUTHOR:-}"

require_java
load_classpath
[[ -d "$QUERY_DIR" ]] || die "query directory not found: $QUERY_DIR"
[[ -d "$INDEX_DIR" ]] || die "index directory not found: $INDEX_DIR — build it first with build-database.sh"

print_accepted_extensions
echo ">> Found $(count_accepted_files "$QUERY_DIR") accepted file(s) under '$QUERY_DIR' (searched recursively)."

mkdir -p "$RESULTS_DIR"
REPORTS_DIR="$RESULTS_DIR/reports"
SUMMARY_FILE="$RESULTS_DIR/matches.txt"

# Assemble CorpusCli arguments. --html-report requires SBERT; it is written for
# every backend, so results always include the highlighted reports. The engine
# does the recursive discovery + filtering.
ARGS=(query --index "$INDEX_DIR" --query "$QUERY_DIR"
      --backend "$BACKEND" --top-k "$TOP_K"
      --sentence-threshold "$SENTENCE_THRESHOLD"
      --extensions "$(extensions_csv)"
      --html-report "$REPORTS_DIR")
[[ -n "$AUTHOR" ]] && ARGS+=(--author "$AUTHOR")

echo ">> Running plagiarism check (backend=$BACKEND, top-k=$TOP_K, sentence-threshold=$SENTENCE_THRESHOLD)…"
# Tee the ranked-match console output into the results directory as well.
java -cp "$JPLAG_CP" "$MAIN_CLASS" "${ARGS[@]}" | tee "$SUMMARY_FILE"

echo
echo "Results saved to: $(abspath "$RESULTS_DIR")"
echo "  ranked matches : $SUMMARY_FILE"
echo "  HTML reports   : $REPORTS_DIR/  (one <document>.html per checked file)"
