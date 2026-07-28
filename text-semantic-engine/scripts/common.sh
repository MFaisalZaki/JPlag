#!/usr/bin/env bash
#
# common.sh — shared configuration and helpers for the plagiarism scripts.
# Sourced by build-database.sh and run-plagiarism.sh (not run directly).
#
# The engine itself now walks the given directory recursively and keeps only the
# accepted extensions (one document per file), so these scripts simply forward
# the directory and the extension list below to it — no staging needed.

set -euo pipefail

# --------------------------------------------------------------------------
# Accepted file extensions (WITHOUT the leading dot), lower-case.
#
# Only these files are indexed / checked; everything else under the directory is
# ignored. Edit this list to widen/narrow what gets plagiarism-checked. These
# are the types the engine ingests natively (.pdf is text-extracted
# automatically); an extension the engine cannot parse will simply yield no
# terms and be skipped.
# --------------------------------------------------------------------------
ACCEPTED_EXTENSIONS=(txt asc tex md rtf csv wiki json yaml yml xml pdf)

# --------------------------------------------------------------------------
# Paths. REPO_ROOT is two levels up from this script (…/JPlag).
# --------------------------------------------------------------------------
COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$COMMON_DIR/.." && pwd)"           # …/text-semantic-engine
REPO_ROOT="$(cd "$MODULE_DIR/.." && pwd)"            # …/JPlag
CLASSPATH_FILE="$MODULE_DIR/target/runtime-classpath.txt"
MAIN_CLASS="de.jplag.text.semantic.CorpusCli"

# Print an error to stderr and exit.
die() { echo "error: $*" >&2; exit 1; }

# Resolve a path to an absolute path (portable; no dependency on `realpath`).
abspath() {
  local target="$1"
  if [[ -d "$target" ]]; then
    (cd "$target" && pwd)
  else
    printf '%s/%s\n' "$(cd "$(dirname "$target")" && pwd)" "$(basename "$target")"
  fi
}

# Ensure a Java runtime is available.
require_java() {
  command -v java >/dev/null 2>&1 || die "'java' not found on PATH. Install a JDK (17+)."
}

# Load the classpath built by build.sh into the JPLAG_CP variable.
load_classpath() {
  [[ -f "$CLASSPATH_FILE" ]] || die "classpath not found at $CLASSPATH_FILE — run scripts/build.sh first."
  JPLAG_CP="$(cat "$CLASSPATH_FILE")"
  [[ -n "$JPLAG_CP" ]] || die "classpath file is empty — re-run scripts/build.sh."
}

# The accepted extensions as a comma-separated string for the engine's --extensions flag.
extensions_csv() {
  local IFS=','
  echo "${ACCEPTED_EXTENSIONS[*]}"
}

# Count accepted files recursively under a directory (for a friendly pre-flight message).
count_accepted_files() {
  local dir="$1" ext count=0 find_expr=()
  for ext in "${ACCEPTED_EXTENSIONS[@]}"; do
    find_expr+=(-iname "*.${ext}" -o)
  done
  unset 'find_expr[${#find_expr[@]}-1]'
  find "$dir" -type f \( "${find_expr[@]}" \) -print0 2>/dev/null | tr -cd '\0' | wc -c | tr -d ' '
}

# Print the accepted-extension list (for logging).
print_accepted_extensions() {
  local IFS=' '
  echo "accepted extensions: ${ACCEPTED_EXTENSIONS[*]}"
}

# --------------------------------------------------------------------------
# At-a-glance summary: one line per checked document, most suspicious first.
#
# With HTML reports each line carries the report's real number — the percentage
# of the document's words matched (unattributed) to indexed sources, summed over
# the per-source percentages in the report's sidebar — plus its top source.
# Lexical-only runs have no reports, so they fall back to the backend's raw
# retrieval score from the ranked-match output (an ordering, not a percentage).
# --------------------------------------------------------------------------

# summarize_from_reports <reports-dir>
summarize_from_reports() {
  local reports_dir="$1" report
  for report in "$reports_dir"/*.html; do
    [[ -e "$report" ]] || continue
    # One awk pass per report: sum the per-source percentages ("pct" spans) and
    # take the first source in the sidebar ("sid" span) as the top source.
    awk -v doc="$(basename "$report" .html)" '
      {
        line = $0
        while (match(line, /class="pct">[0-9]+%/)) {
          total += substr(line, RSTART + 12, RLENGTH - 13) + 0
          line = substr(line, RSTART + RLENGTH)
        }
        if (top == "" && match($0, /class="sid">(<a[^>]*>)?[^<]+/)) {
          top = substr($0, RSTART, RLENGTH)
          sub(/.*>/, "", top)
        }
      }
      END {
        if (top == "") top = "(no matches)"
        gsub(/&amp;/, "\\&", top)
        printf "%d%%\t%s\t%s\n", total, doc, top
      }
    ' "$report"
  done | sort -t $'\t' -rn -k1,1
}

# summarize_from_scores <matches-file>
summarize_from_scores() {
  awk '
    function flush() { if (doc != "" && !have) printf "0.0000\t%s\t(no matches)\n", doc; doc = "" }
    / -- top [0-9]+ matches \(/ {
      flush()
      doc = $0
      sub(/ -- top [0-9]+ matches \(.*$/, "", doc)
      have = 0
      next
    }
    /^  [0-9][0-9.]*  / && doc != "" && !have {
      src = $0
      sub(/^[[:space:]]*[0-9.]+[[:space:]]+/, "", src)
      printf "%s\t%s\t%s\n", $1, doc, src
      have = 1
    }
    END { flush() }
  ' "$1" | sort -t $'\t' -rn -k1,1
}

# write_summary_table <reports-dir|""> <matches-file> <out-file>
# Pass an empty reports directory to fall back to raw retrieval scores.
write_summary_table() {
  local reports_dir="$1" matches_file="$2" out_file="$3"
  {
    if [[ -n "$reports_dir" ]]; then
      printf 'matched\tdocument\ttop-source\n'
      summarize_from_reports "$reports_dir"
    else
      printf 'top-score\tdocument\ttop-match\n'
      summarize_from_scores "$matches_file"
    fi
  } | column -t -s $'\t' > "$out_file"
}
