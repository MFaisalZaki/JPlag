#!/usr/bin/env bash
#
# build-image.sh — build the plagiarism-check Apptainer image.
#
# Usage:
#   text-semantic-engine/apptainer/build-image.sh [output.sif]
#
# Runs `apptainer build` from the repository root, which is where the definition
# file's %files paths are anchored. Where Apptainer itself is not installed —
# macOS, most notably, since Apptainer is Linux-only — it falls back to running
# the official Apptainer image under Docker or Podman, which produces the same
# .sif. That fallback needs a privileged container; if your daemon forbids that,
# build on a Linux host instead.
#
# The build needs network access (Maven dependencies, the SBERT model, the
# PyTorch runtime) and takes roughly 15-25 minutes the first time. The finished
# image needs no network at all.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEFINITION="text-semantic-engine/apptainer/plagiarism-check.def"
OUTPUT="${1:-$SCRIPT_DIR/plagiarism-check.sif}"
# Apptainer packaged as a container image, for the no-Apptainer fallback below.
# Apptainer's own quay.io repository is not publicly readable; this is the
# long-standing community build of the same releases.
APPTAINER_IMAGE="${APPTAINER_IMAGE:-docker.io/kaczmarj/apptainer:1.4.4}"

die() { echo "error: $*" >&2; exit 1; }

cd "$REPO_ROOT"
[[ -f "$DEFINITION" ]] || die "definition file not found at $REPO_ROOT/$DEFINITION"
[[ -d "$(dirname "$OUTPUT")" ]] || die "no such directory for the image: $(dirname "$OUTPUT")"

# Apptainer refuses to overwrite an existing image without --force.
[[ -e "$OUTPUT" ]] && { echo ">> removing the existing $OUTPUT"; rm -f "$OUTPUT"; }

if command -v apptainer >/dev/null 2>&1; then
  echo ">> apptainer build $OUTPUT $DEFINITION  (from $REPO_ROOT)"
  apptainer build "$OUTPUT" "$DEFINITION"
elif command -v singularity >/dev/null 2>&1; then
  echo ">> singularity build $OUTPUT $DEFINITION  (from $REPO_ROOT)"
  singularity build "$OUTPUT" "$DEFINITION"
else
  # No native Apptainer: drive it from inside a container instead. The repository
  # is mounted at the same path the definition expects to be run from, and the
  # image is written straight back out to the host.
  RUNNER=""
  for candidate in docker podman; do
    command -v "$candidate" >/dev/null 2>&1 && { RUNNER="$candidate"; break; }
  done
  [[ -n "$RUNNER" ]] || die "no apptainer, singularity, docker or podman found — install one of them."

  OUTPUT_DIR="$(cd "$(dirname "$OUTPUT")" && pwd)"
  OUTPUT_NAME="$(basename "$OUTPUT")"
  echo ">> apptainer is not installed; building via $RUNNER ($APPTAINER_IMAGE)"
  echo ">> this needs a privileged container, and produces an image for THIS"
  echo ">> machine's architecture ($(uname -m)) — see apptainer/README.md"
  "$RUNNER" run --rm --privileged \
    -v "$REPO_ROOT:/build" \
    -v "$OUTPUT_DIR:/out" \
    -w /build \
    "$APPTAINER_IMAGE" \
    build "/out/$OUTPUT_NAME" "$DEFINITION"
fi

echo
echo "Image built: $OUTPUT"
echo "Run it with:"
echo "  apptainer run \"$OUTPUT\" <dataset-root> <output-root>"
