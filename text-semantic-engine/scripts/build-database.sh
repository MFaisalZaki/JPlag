#!/usr/bin/env bash
#
# build-database.sh — construct (or extend) the searchable corpus index from a
# directory of documents. This is the "database" you query later with
# run-plagiarism.sh.
#
# The engine searches <docs-dir> RECURSIVELY and indexes every accepted file
# (see common.sh) as its own document. Re-running against the same index appends
# to it (the index is incremental).
#
# Usage:
#   scripts/build-database.sh <docs-dir> <index-dir>
#
# Options (environment variables):
#   AUTHOR=<name>            tag every document with this author (enables
#                            self-plagiarism detection at query time).
#   AUTHOR_PATTERN=<regex>   derive each file's author from its file name (first
#                            capture group), e.g. '^([0-9]+)-' for
#                            '<studentid>-essay.pdf'; unmatched files fall back
#                            to AUTHOR.
#   COAUTHOR_PATTERN=<regex> every match of this regex on a document's cover
#                            sheet (its first 2000 characters) is a co-author,
#                            e.g. '\b2[0-9]{8}\b' for student ids. Needed for
#                            paired/group courseworks, where each member submits
#                            the same document under their own name.
#   NO_EMBEDDINGS=1          build a lexical-only (BM25) index; skips the SBERT
#                            model download. Faster, but disables semantic/HTML
#                            queries.
#
# Examples:
#   scripts/build-database.sh ./past-submissions ./corpus-index
#   AUTHOR=alice scripts/build-database.sh ./alice-prior-work ./corpus-index
#   AUTHOR_PATTERN='^([0-9]+)-' scripts/build-database.sh ./submissions ./corpus-index
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

usage() { sed -n '2,36p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then usage; exit 0; fi
[[ $# -eq 2 ]] || { usage; die "expected 2 arguments, got $#."; }

DOCS_DIR="$1"
INDEX_DIR="$2"
AUTHOR="${AUTHOR:-}"
AUTHOR_PATTERN="${AUTHOR_PATTERN:-}"
COAUTHOR_PATTERN="${COAUTHOR_PATTERN:-}"

require_java
load_classpath
[[ -d "$DOCS_DIR" ]] || die "documents directory not found: $DOCS_DIR"

print_accepted_extensions
echo ">> Found $(count_accepted_files "$DOCS_DIR") accepted file(s) under '$DOCS_DIR' (searched recursively)."

# Assemble CorpusCli arguments; the engine does the recursive discovery + filtering.
ARGS=(index --index "$INDEX_DIR" "$DOCS_DIR" --extensions "$(extensions_csv)")
[[ -n "$AUTHOR" ]] && ARGS+=(--author "$AUTHOR")
[[ -n "$AUTHOR_PATTERN" ]] && ARGS+=(--author-pattern "$AUTHOR_PATTERN")
[[ -n "$COAUTHOR_PATTERN" ]] && ARGS+=(--coauthor-pattern "$COAUTHOR_PATTERN")
[[ "${NO_EMBEDDINGS:-0}" == "1" ]] && ARGS+=(--no-embeddings)

echo ">> Building index at '$INDEX_DIR'${AUTHOR:+ (author: $AUTHOR)}…"
java -cp "$JPLAG_CP" "$MAIN_CLASS" "${ARGS[@]}"

echo
echo "Database ready at: $(abspath "$INDEX_DIR")"
echo "Query it with: scripts/run-plagiarism.sh <query-dir> \"$INDEX_DIR\" <results-dir>"
