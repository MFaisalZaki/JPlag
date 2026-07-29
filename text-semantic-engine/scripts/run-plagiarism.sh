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
#   AUTHOR=<name>                   flag reuse of this author's own indexed work
#                                   as self-plagiarism.
#   AUTHOR_PATTERN=<regex>          derive each query file's author from its file
#                                   name (first capture group), e.g. '^([0-9]+)-'
#                                   for '<studentid>-essay.pdf'; unmatched files
#                                   fall back to AUTHOR.
#   COAUTHOR_PATTERN=<regex>        every match of this regex on a document's
#                                   cover sheet is a co-author, e.g.
#                                   '\b2[0-9]{8}\b' for student ids. Needed for
#                                   paired/group courseworks, where each member
#                                   submits the same document under their own
#                                   name; must match what was used at index time.
#   SAME_AUTHOR=<exclude|flag>      what to do with matches to the query author's
#                                   own indexed work: 'exclude' (default) drops
#                                   them (a resubmission is then not reported at
#                                   all), 'flag' marks them as self-plagiarism.
#   COMMON_SENTENCE_SHARE=<0-1>     share of the checked set above which a
#                                   sentence counts as given material (assignment
#                                   brief, prescribed method, template) and is
#                                   left out of the check (default: 0.10). 0
#                                   disables it; it is ignored below 10 documents.
#   CHECK_ALL_SECTIONS=<1|0>        1 also checks cover sheets and reference
#                                   lists (default: 0). They are identical across
#                                   a cohort by design, so checking them scores
#                                   every submission highly and shows nothing.
#   MINIMUM_WORD_OVERLAP=<0-1>      literal word overlap a match must reach on
#                                   top of the sentence threshold (default: 0).
#                                   Leave at 0 when the index holds the cohort's
#                                   own submissions. Raise it — 0.4 for lightly
#                                   edited, 0.8 for near-verbatim — when the index
#                                   holds published or reference material: a
#                                   subject's standard sentences ("Km is the
#                                   substrate concentration at which the velocity
#                                   is half of Vmax") are semantically identical
#                                   for everyone who states them correctly, so
#                                   there the wording has to decide, not the
#                                   embedding.
#
# Examples:
#   scripts/run-plagiarism.sh ./new-submissions ./corpus-index ./results
#   BACKEND=SBERT TOP_K=10 scripts/run-plagiarism.sh ./essays ./corpus-index ./out
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

usage() { sed -n '2,55p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

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
AUTHOR="${AUTHOR:-}"
AUTHOR_PATTERN="${AUTHOR_PATTERN:-}"
COAUTHOR_PATTERN="${COAUTHOR_PATTERN:-}"
SAME_AUTHOR="${SAME_AUTHOR:-exclude}"
COMMON_SENTENCE_SHARE="${COMMON_SENTENCE_SHARE:-0.10}"
CHECK_ALL_SECTIONS="${CHECK_ALL_SECTIONS:-0}"
MINIMUM_WORD_OVERLAP="${MINIMUM_WORD_OVERLAP:-0}"
[[ "$SAME_AUTHOR" == "exclude" || "$SAME_AUTHOR" == "flag" ]] || die "SAME_AUTHOR must be 'exclude' or 'flag', got '$SAME_AUTHOR'."

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
      --common-sentence-share "$COMMON_SENTENCE_SHARE"
      --minimum-word-overlap "$MINIMUM_WORD_OVERLAP"
      --extensions "$(extensions_csv)"
      --html-report "$REPORTS_DIR")
[[ -n "$AUTHOR" ]] && ARGS+=(--author "$AUTHOR")
[[ -n "$AUTHOR_PATTERN" ]] && ARGS+=(--author-pattern "$AUTHOR_PATTERN")
[[ -n "$COAUTHOR_PATTERN" ]] && ARGS+=(--coauthor-pattern "$COAUTHOR_PATTERN")
[[ "$SAME_AUTHOR" == "flag" ]] && ARGS+=(--no-exclude-same-author)
[[ "$CHECK_ALL_SECTIONS" == "1" ]] && ARGS+=(--check-all-sections)

echo ">> Running plagiarism check (backend=$BACKEND, top-k=$TOP_K, sentence-threshold=$SENTENCE_THRESHOLD)…"
# Tee the ranked-match console output into the results directory as well.
java -cp "$JPLAG_CP" "$MAIN_CLASS" "${ARGS[@]}" | tee "$SUMMARY_FILE"

echo
echo "Results saved to: $(abspath "$RESULTS_DIR")"
echo "  ranked matches : $SUMMARY_FILE"
echo "  HTML reports   : $REPORTS_DIR/  (one <document>.html per checked file)"
