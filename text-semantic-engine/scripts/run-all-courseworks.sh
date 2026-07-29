#!/usr/bin/env bash
#
# run-all-courseworks.sh — plagiarism-check a whole term's worth of modules, one
# independent check per coursework, and record time/memory statistics.
#
# Expected input layout (two levels: module, then coursework):
#
#     <dataset-root>/
#       <module>/                       e.g. SD2005
#         <coursework>/                 e.g. 883461
#           <studentid>-<name>-<id>.pdf   the submissions
#           warned/                       submissions that received a warning
#             <studentid>-<name>-<id>.pdf
#
# The output mirrors that structure exactly:
#
#     <output-root>/
#       <module>/<coursework>/
#         reports/<document>.html   one originality report per submission
#         matches.txt               ranked source matches per submission
#         summary.txt               each submission's matched % and top source
#         run.log                   full engine output for this coursework
#         index/                    the coursework's corpus index (see KEEP_INDEX)
#       stats.csv                   one row per coursework (machine readable)
#       stats.txt                   the same as a table, with averages
#       run.log                     this script's own progress log
#
# Each coursework is checked ENTIRELY ON ITS OWN: its own index, its own
# reports, no cross-module comparison. Every file under the coursework —
# including everything in warned/ — is both indexed and checked, so warned
# submissions are compared against the regular ones AND against each other. A
# document is never matched against itself, nor against its own author's other
# submissions (AUTHOR_PATTERN derives the author from the file name, so a
# resubmission of the same essay is not reported as plagiarism).
#
# Usage:
#   scripts/run-all-courseworks.sh <dataset-root> <output-root>
#
# Options (environment variables):
#   BACKEND=<TFIDF|SBERT|ENSEMBLE>  retrieval signal (default: ENSEMBLE).
#   TOP_K=<n|all>                   archived documents to compare each submission
#                                   against (default: all).
#   SENTENCE_THRESHOLD=<0-1>        sentence match cutoff (default: 0.85).
#   AUTHOR_PATTERN=<regex>          regex extracting the author (student id) from
#                                   each file name; default '^([0-9]+)-' suits
#                                   '<studentid>-<assignment>-<submissionid>.pdf'.
#                                   Set to '' to disable author handling.
#   COAUTHOR_PATTERN=<regex>        every match of this regex on a document's
#                                   cover sheet is a co-author; default
#                                   '\b2[0-9]{8}\b' picks up every student id on
#                                   it, so both members of a paired submission
#                                   own it and neither is reported against the
#                                   other. Set to '' to disable.
#   SAME_AUTHOR=<exclude|flag>      matches to the same author's other work:
#                                   'exclude' (default) drops them, 'flag' shows
#                                   them as self-reuse.
#   FLAG_THRESHOLD=<0-100>          matched percentage at or above which a
#                                   document is counted as flagged in the
#                                   statistics (default: 20). Documents below it
#                                   still get a report; this is the size of the
#                                   queue a marker has to read.
#   KEEP_INDEX=<1|0>                keep each coursework's index (default: 1).
#                                   0 deletes it after the check to save disk.
#   RESUME=<1|0>                    skip courseworks that already have a
#                                   summary.txt (default: 0). Use to continue an
#                                   interrupted run.
#   ONLY=<patterns>                 only process courseworks whose
#                                   '<module>/<coursework>' path matches one of
#                                   these comma-separated shell patterns, e.g.
#                                   ONLY='SD2005/*' or ONLY='SD2005/883465,PN1001/*'.
#
# Exits non-zero if any coursework failed; the ones that succeeded still have
# their results and their row in stats.csv.
#
# Examples:
#   scripts/run-all-courseworks.sh ../2025_6 ../2025_6-results
#   ONLY='SD2005/*' scripts/run-all-courseworks.sh ../2025_6 ../2025_6-results
#   RESUME=1 KEEP_INDEX=0 scripts/run-all-courseworks.sh ../2025_6 ../out
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

usage() { sed -n '2,79p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then usage; exit 0; fi
[[ $# -eq 2 ]] || { usage; die "expected 2 arguments, got $#."; }

DATASET_ROOT="$1"
OUTPUT_ROOT="$2"
BACKEND="${BACKEND:-ENSEMBLE}"
TOP_K="${TOP_K:-all}"
SENTENCE_THRESHOLD="${SENTENCE_THRESHOLD:-0.85}"
# The dataset's file names are '<studentid>-<assignment>-<submissionid>.pdf', so
# the leading id identifies the author and lets resubmissions be excluded.
AUTHOR_PATTERN="${AUTHOR_PATTERN-^([0-9]+)-}"
# Paired and group courseworks are submitted once per member, so a submission
# belongs to every student id printed on its cover sheet, not just the one in the
# file name. Without this each partner's copy is a 100% match against the other.
# Assigned via a variable: a '{n}' quantifier inside ${VAR-default} is eaten by
# brace expansion, which silently produces an invalid regex.
DEFAULT_COAUTHOR_PATTERN='\b2[0-9]{8}\b'
COAUTHOR_PATTERN="${COAUTHOR_PATTERN-$DEFAULT_COAUTHOR_PATTERN}"
SAME_AUTHOR="${SAME_AUTHOR:-exclude}"
FLAG_THRESHOLD="${FLAG_THRESHOLD:-20}"
KEEP_INDEX="${KEEP_INDEX:-1}"
RESUME="${RESUME:-0}"
ONLY="${ONLY:-}"

[[ -d "$DATASET_ROOT" ]] || die "dataset directory not found: $DATASET_ROOT"
[[ "$SAME_AUTHOR" == "exclude" || "$SAME_AUTHOR" == "flag" ]] || die "SAME_AUTHOR must be 'exclude' or 'flag', got '$SAME_AUTHOR'."

require_java
# Build the engine on first use so this stays a one-command workflow.
if [[ ! -f "$CLASSPATH_FILE" ]]; then
  echo ">> Engine not built yet — running scripts/build.sh first…"
  "$SCRIPT_DIR/build.sh"
fi
load_classpath

DATASET_ROOT="$(abspath "$DATASET_ROOT")"
mkdir -p "$OUTPUT_ROOT"
OUTPUT_ROOT="$(abspath "$OUTPUT_ROOT")"
STATS_CSV="$OUTPUT_ROOT/stats.csv"
STATS_TXT="$OUTPUT_ROOT/stats.txt"
RUN_LOG="$OUTPUT_ROOT/run.log"
: > "$RUN_LOG"

# Log to the console and to the run log at once.
say() { echo "$@" | tee -a "$RUN_LOG"; }

# --------------------------------------------------------------------------
# Measurement. /usr/bin/time reports the peak resident set size of the command
# and its children, which is what we want: the engine's real cost is the JVM the
# wrapper scripts spawn, not the shell wrapping it. GNU time (Linux, and so the
# container) and BSD time (macOS) disagree on both the flags and the units, so
# pick the dialect once here; without either, fall back to the shell's clock and
# report no memory figure rather than failing the run.
# --------------------------------------------------------------------------
if /usr/bin/time -f '' true >/dev/null 2>&1; then
  TIME_STYLE=gnu        # custom format, peak RSS in kilobytes
elif /usr/bin/time -l true >/dev/null 2>&1; then
  TIME_STYLE=bsd        # fixed report, peak RSS in bytes
else
  TIME_STYLE=none
  echo ">> note: /usr/bin/time not available — memory figures will be reported as 0."
fi

# measure <log-file> <command> [args...]  ->  MEASURED_SECONDS, MEASURED_PEAK_MB,
#                                             MEASURED_STATUS
measure() {
  local log="$1"; shift
  local timing status=0 start=$SECONDS
  timing="$(mktemp)"
  # stdout goes to the log; stderr carries both the command's logging and
  # time's report, so it is parsed for the figures and then appended too.
  case "$TIME_STYLE" in
    gnu) /usr/bin/time -f 'jplag-time real %e peak_kb %M' "$@" >>"$log" 2>"$timing" || status=$? ;;
    bsd) /usr/bin/time -l "$@" >>"$log" 2>"$timing" || status=$? ;;
    *)   "$@" >>"$log" 2>"$timing" || status=$? ;;
  esac
  MEASURED_STATUS="$status"
  MEASURED_SECONDS="$(awk -v style="$TIME_STYLE" -v fallback="$((SECONDS - start))" '
    style == "gnu" && /^jplag-time real /          { seconds = $3 }
    style == "bsd" && / real /                     { seconds = $1 }
    END { printf "%.1f", (seconds == "" ? fallback : seconds) + 0 }' "$timing")"
  MEASURED_PEAK_MB="$(awk -v style="$TIME_STYLE" '
    style == "gnu" && /^jplag-time real /          { megabytes = $5 / 1024 }
    style == "bsd" && /maximum resident set size/  { megabytes = $1 / 1048576 }
    END { printf "%.0f", megabytes + 0 }' "$timing")"
  cat "$timing" >>"$log"
  rm -f "$timing"
}

# Count accepted files directly in a directory (not recursively).
count_files_at() {
  local dir="$1" ext count=0 find_expr=()
  [[ -d "$dir" ]] || { echo 0; return; }
  for ext in "${ACCEPTED_EXTENSIONS[@]}"; do
    find_expr+=(-iname "*.${ext}" -o)
  done
  unset 'find_expr[${#find_expr[@]}-1]'
  find "$dir" -maxdepth 1 -type f \( "${find_expr[@]}" \) -print0 2>/dev/null | tr -cd '\0' | wc -c | tr -d ' '
}

# True if a '<module>/<coursework>' path matches any of the comma-separated
# shell patterns in ONLY, so a run can cover a chosen handful of courseworks.
matches_only() {
  local relative="$1" pattern
  local IFS=','
  for pattern in $ONLY; do
    # shellcheck disable=SC2053  # the pattern is meant to glob
    [[ "$relative" == $pattern ]] && return 0
  done
  return 1
}

# --------------------------------------------------------------------------
# Discover the courseworks: every <module>/<coursework> directory holding at
# least one accepted file (anywhere below it).
# --------------------------------------------------------------------------
COURSEWORKS=()
while IFS= read -r directory; do
  relative="${directory#"$DATASET_ROOT"/}"
  [[ "$(basename "$directory")" == "warned" ]] && continue
  [[ -n "$ONLY" ]] && ! matches_only "$relative" && continue
  [[ "$(count_accepted_files "$directory")" -gt 0 ]] || continue
  COURSEWORKS+=("$relative")
done < <(find "$DATASET_ROOT" -mindepth 2 -maxdepth 2 -type d | sort)

[[ "${#COURSEWORKS[@]}" -gt 0 ]] || die "no courseworks with accepted files found under '$DATASET_ROOT'${ONLY:+ matching ONLY='$ONLY'}."

print_accepted_extensions | tee -a "$RUN_LOG"
say ">> ${#COURSEWORKS[@]} coursework(s) to check under $DATASET_ROOT"
say ">> backend=$BACKEND top-k=$TOP_K sentence-threshold=$SENTENCE_THRESHOLD same-author=$SAME_AUTHOR${AUTHOR_PATTERN:+ author-pattern='$AUTHOR_PATTERN'}"
say ">> output -> $OUTPUT_ROOT"
say ""

# Resuming keeps the statistics already collected, so an interrupted run does not
# lose the timings of the courseworks it did finish. A coursework counts as done
# only when it has a row here, so one interrupted mid-write is simply redone.
ALREADY_DONE=""
if [[ "$RESUME" == "1" && -f "$STATS_CSV" ]]; then
  ALREADY_DONE="$(tail -n +2 "$STATS_CSV" | awk -F, 'NF > 1 {print $1 "/" $2}')"
  say ">> RESUME=1: $(grep -c . <<<"$ALREADY_DONE") coursework(s) already recorded, keeping their statistics"
else
  printf 'module,coursework,submissions,warned,documents,flagged,index_seconds,query_seconds,total_seconds,peak_mb,status\n' > "$STATS_CSV"
fi

INDEX=0
FAILURES=0
START_ALL=$SECONDS

for relative in "${COURSEWORKS[@]}"; do
  INDEX=$((INDEX + 1))
  module="${relative%%/*}"
  coursework="${relative##*/}"
  source_dir="$DATASET_ROOT/$relative"
  result_dir="$OUTPUT_ROOT/$relative"
  log="$result_dir/run.log"

  submissions="$(count_files_at "$source_dir")"
  warned="$(count_files_at "$source_dir/warned")"
  documents="$(count_accepted_files "$source_dir")"

  if [[ -n "$ALREADY_DONE" ]] && grep -qxF "$relative" <<<"$ALREADY_DONE"; then
    say "[$INDEX/${#COURSEWORKS[@]}] $relative — already done, skipping (RESUME=1)"
    continue
  fi

  say "[$INDEX/${#COURSEWORKS[@]}] $relative — $documents document(s): $submissions submission(s) + $warned warned"

  mkdir -p "$result_dir"
  rm -rf "$result_dir/index" "$result_dir/reports"
  : > "$log"

  # Index the whole coursework, warned submissions included, so every document
  # can be matched against every other one.
  export AUTHOR_PATTERN COAUTHOR_PATTERN
  measure "$log" "$SCRIPT_DIR/build-database.sh" "$source_dir" "$result_dir/index"
  index_seconds="$MEASURED_SECONDS"
  index_peak="$MEASURED_PEAK_MB"
  index_status="$MEASURED_STATUS"

  query_seconds=0
  query_peak=0
  query_status=0
  if [[ "$index_status" -eq 0 ]]; then
    export BACKEND TOP_K SENTENCE_THRESHOLD SAME_AUTHOR
    measure "$log" "$SCRIPT_DIR/run-plagiarism.sh" "$source_dir" "$result_dir/index" "$result_dir"
    query_seconds="$MEASURED_SECONDS"
    query_peak="$MEASURED_PEAK_MB"
    query_status="$MEASURED_STATUS"
  fi

  status="ok"
  if [[ "$index_status" -ne 0 || "$query_status" -ne 0 ]]; then
    status="FAILED"
    FAILURES=$((FAILURES + 1))
    say "    !! failed (index=$index_status query=$query_status) — see $log"
  fi

  # One line per document, most suspicious first. A document counts as flagged
  # only at or above FLAG_THRESHOLD: nearly every document matches something, so
  # "matched more than nothing" is not a queue anyone can work through.
  flagged=0
  if [[ -d "$result_dir/reports" ]]; then
    write_summary_table "$result_dir/reports" "$result_dir/matches.txt" "$result_dir/summary.txt"
    flagged="$(awk -v threshold="$FLAG_THRESHOLD" 'NR > 1 && $1 + 0 >= threshold {count++} END{print count+0}' "$result_dir/summary.txt")"
  fi

  [[ "$KEEP_INDEX" == "1" ]] || rm -rf "$result_dir/index"

  total_seconds="$(awk -v a="$index_seconds" -v b="$query_seconds" 'BEGIN{printf "%.1f", a+b}')"
  peak_mb="$(awk -v a="$index_peak" -v b="$query_peak" 'BEGIN{print (a>b)?a:b}')"

  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$module" "$coursework" "$submissions" "$warned" "$documents" "$flagged" \
    "$index_seconds" "$query_seconds" "$total_seconds" "$peak_mb" "$status" >> "$STATS_CSV"

  say "    $status — ${total_seconds}s (index ${index_seconds}s + query ${query_seconds}s), peak ${peak_mb} MB, $flagged/$documents document(s) at or above ${FLAG_THRESHOLD}%"
done

ELAPSED_ALL=$((SECONDS - START_ALL))

# --------------------------------------------------------------------------
# Statistics: per coursework, rolled up per module, then averaged overall.
# --------------------------------------------------------------------------
{
  echo "Plagiarism check — $DATASET_ROOT"
  echo "backend=$BACKEND  top-k=$TOP_K  sentence-threshold=$SENTENCE_THRESHOLD  same-author=$SAME_AUTHOR"
  echo "flag-threshold=${FLAG_THRESHOLD}%"
  echo
  echo "PER COURSEWORK"
  {
    printf 'module\tcoursework\tdocs\twarned\tflagged\tindex_s\tquery_s\ttotal_s\tpeak_mb\tstatus\n'
    awk -F, 'NR > 1 {printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", $1, $2, $5, $4, $6, $7, $8, $9, $10, $11}' "$STATS_CSV"
  } | column -t -s $'\t'
  echo
  echo "PER MODULE"
  {
    printf 'module\tcourseworks\tdocs\tflagged\ttotal_s\tavg_s\tpeak_mb\n'
    awk -F, 'NR > 1 {
      count[$1]++; docs[$1] += $5; flagged[$1] += $6; seconds[$1] += $9
      if ($10 + 0 > peak[$1]) peak[$1] = $10
    }
    END {
      for (module in count) {
        printf "%s\t%d\t%d\t%d\t%.1f\t%.1f\t%d\n", module, count[module], docs[module], flagged[module], seconds[module], seconds[module] / count[module], peak[module]
      }
    }' "$STATS_CSV" | sort
  } | column -t -s $'\t'
  echo
  echo "OVERALL"
  awk -F, -v wall="$ELAPSED_ALL" -v failures="$FAILURES" -v threshold="$FLAG_THRESHOLD" 'NR > 1 {
    count++; docs += $5; warned += $4; flagged += $6
    index_s += $7; query_s += $8; total_s += $9
    peak_sum += $10; if ($10 + 0 > peak_max) peak_max = $10
  }
  END {
    if (count == 0) { print "  no courseworks processed"; exit }
    printf "  courseworks      : %d (%d failed)\n", count, failures
    printf "  documents        : %d (%d warned)\n", docs, warned
    printf "  flagged (>=%s%%)  : %d (%.1f%% of documents)\n", threshold, flagged, 100 * flagged / docs
    printf "  wall-clock       : %dm %ds (this run)\n", wall / 60, wall % 60
    printf "  engine time      : %.1fs total (index %.1fs + query %.1fs)\n", total_s, index_s, query_s
    printf "  AVERAGE per coursework: %.1fs  (index %.1fs + query %.1fs), %.0f MB peak\n", total_s / count, index_s / count, query_s / count, peak_sum / count
    printf "  AVERAGE per document  : %.2fs\n", total_s / docs
    printf "  PEAK memory (any run) : %d MB\n", peak_max
  }' "$STATS_CSV"
} > "$STATS_TXT"

cat "$STATS_TXT" | tee -a "$RUN_LOG"

say ""
say "Results saved to: $OUTPUT_ROOT"
say "  per coursework : <module>/<coursework>/{reports/,matches.txt,summary.txt,run.log}"
say "  statistics     : $STATS_TXT  (and $STATS_CSV)"
[[ "$FAILURES" -eq 0 ]] || say "  $FAILURES coursework(s) FAILED — see their run.log"

# Report failures in the exit status too: unattended runs — a batch scheduler, or
# the container's entry point — see nothing else, and the results of the
# courseworks that did work are already on disk either way.
[[ "$FAILURES" -eq 0 ]] || exit 1
exit 0
