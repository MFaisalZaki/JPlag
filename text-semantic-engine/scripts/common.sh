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
