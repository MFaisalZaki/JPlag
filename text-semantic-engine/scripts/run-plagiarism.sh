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
#   TOP_K=<n|all>                   archived documents to compare each query against,
#                                   most similar first. 'all' (default) compares
#                                   against the whole index, so nothing is missed
#                                   because retrieval ranked it low; retrieval then
#                                   only orders the results. Set a number for a
#                                   corpus too large to compare in full.
#   SENTENCE_THRESHOLD=<0-1>        sentence match cutoff for the report (default: 0.85).
#                                   Embeddings rate any two sentences on the same
#                                   topic highly, so lower values report shared
#                                   subject matter rather than reuse.
#   MIN_LEXICAL_OVERLAP=<0-1>       distinctive wording a match must share with its
#                                   source, weighted by how rare each word is across
#                                   the documents compared, so a cohort's topic
#                                   vocabulary is not evidence. Default 0 reports
#                                   semantic similarity alone; 0.10 suppresses
#                                   matches that share only their subject.
#   MAX_SOURCE_FRACTION=<0-1>       share of candidate sources a passage may match
#                                   before it counts as material they all share, eg
#                                   a common citation. Default 1 disables the check;
#                                   0.75 suits a cohort answering one prompt.
#   BOILERPLATE=<include|exclude>   assignment cover sheets and academic-integrity
#                                   declarations: 'include' (default) reports them
#                                   like any other text; 'exclude' drops them, worth
#                                   setting for a cohort sharing a cover sheet since
#                                   identical front matter matches near-perfectly.
#   AUTHOR=<name>                   flag reuse of this author's own indexed work
#                                   as self-plagiarism.
#   AUTHOR_PATTERN=<regex>          derive each query file's author from its file
#                                   name (first capture group), e.g. '^([0-9]+)-'
#                                   for '<studentid>-essay.pdf'; unmatched files
#                                   fall back to AUTHOR.
#   SAME_AUTHOR=<exclude|flag>      what to do with matches to the query author's
#                                   own indexed work: 'exclude' (default) drops
#                                   them (a resubmission is then not reported at
#                                   all), 'flag' marks them as self-plagiarism.
#
# Examples:
#   scripts/run-plagiarism.sh ./new-submissions ./corpus-index ./results
#   BACKEND=SBERT TOP_K=10 scripts/run-plagiarism.sh ./essays ./corpus-index ./out
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

usage() { sed -n '2,54p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then usage; exit 0; fi
[[ $# -eq 3 ]] || { usage; die "expected 3 arguments, got $#."; }

QUERY_DIR="$1"
INDEX_DIR="$2"
RESULTS_DIR="$3"
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
MIN_LEXICAL_OVERLAP="${MIN_LEXICAL_OVERLAP:-0}"
MAX_SOURCE_FRACTION="${MAX_SOURCE_FRACTION:-1}"
BOILERPLATE="${BOILERPLATE:-include}"
AUTHOR="${AUTHOR:-}"
AUTHOR_PATTERN="${AUTHOR_PATTERN:-}"
SAME_AUTHOR="${SAME_AUTHOR:-exclude}"
[[ "$SAME_AUTHOR" == "exclude" || "$SAME_AUTHOR" == "flag" ]] || die "SAME_AUTHOR must be 'exclude' or 'flag', got '$SAME_AUTHOR'."
[[ "$BOILERPLATE" == "exclude" || "$BOILERPLATE" == "include" ]] || die "BOILERPLATE must be 'exclude' or 'include', got '$BOILERPLATE'."

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
      --backend "$BACKEND" --top-k "$TOP_K_ARG"
      --sentence-threshold "$SENTENCE_THRESHOLD"
      --min-lexical-overlap "$MIN_LEXICAL_OVERLAP"
      --max-source-fraction "$MAX_SOURCE_FRACTION"
      --extensions "$(extensions_csv)"
      --html-report "$REPORTS_DIR")
[[ -n "$AUTHOR" ]] && ARGS+=(--author "$AUTHOR")
[[ -n "$AUTHOR_PATTERN" ]] && ARGS+=(--author-pattern "$AUTHOR_PATTERN")
[[ "$SAME_AUTHOR" == "flag" ]] && ARGS+=(--no-exclude-same-author)
[[ "$BOILERPLATE" == "exclude" ]] && ARGS+=(--exclude-boilerplate)

echo ">> Running plagiarism check (backend=$BACKEND, top-k=$TOP_K, sentence-threshold=$SENTENCE_THRESHOLD," \
     "min-lexical-overlap=$MIN_LEXICAL_OVERLAP, max-source-fraction=$MAX_SOURCE_FRACTION, boilerplate=$BOILERPLATE)…"
# Tee the ranked-match console output into the results directory as well.
java -cp "$JPLAG_CP" "$MAIN_CLASS" "${ARGS[@]}" | tee "$SUMMARY_FILE"

echo
echo "Results saved to: $(abspath "$RESULTS_DIR")"
echo "  ranked matches : $SUMMARY_FILE"
echo "  HTML reports   : $REPORTS_DIR/  (one <document>.html per checked file)"
