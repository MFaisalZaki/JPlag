#!/usr/bin/env bash
#
# build.sh — compile the JPlag semantic text engine and record its runtime
# classpath so the other scripts can launch it with plain `java`.
#
# Run this once (and again after code changes). It:
#   1. builds the text-semantic-engine module and the modules it depends on,
#   2. writes the full runtime classpath to target/runtime-classpath.txt,
#      which build-database.sh and run-plagiarism.sh read.
#
# Usage: scripts/build.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

require_java
command -v mvn >/dev/null 2>&1 || die "'mvn' (Maven) not found on PATH."

echo ">> Building text-semantic-engine (and its dependencies)…"
# -am also builds the sibling modules the engine depends on and installs them to
# the local repo so the classpath step below can resolve them. Skip the slow
# quality gates that are irrelevant to producing runnable classes.
mvn -f "$REPO_ROOT/pom.xml" -pl text-semantic-engine -am install \
  -DskipTests \
  -Dspotless.check.skip=true -Dspotless.apply.skip=true \
  -Dcheckstyle.skip=true -Denforcer.skip=true -Dmaven.javadoc.skip=true

echo ">> Resolving runtime classpath…"
# Not offline (-o): the dependency plugin is not bound to the build lifecycle, so
# on a cold local repository — a fresh machine, or the container build — the step
# above never fetches it. Released artefacts are not re-checked remotely anyway.
DEPS_FILE="$MODULE_DIR/target/dependency-classpath.txt"
mvn -f "$REPO_ROOT/pom.xml" -pl text-semantic-engine \
  dependency:build-classpath -Dmdep.outputFile="$DEPS_FILE" -q

# The module's own compiled classes come first, then all dependency jars.
printf '%s:%s\n' "$MODULE_DIR/target/classes" "$(cat "$DEPS_FILE")" > "$CLASSPATH_FILE"

echo ">> Verifying the CLI launches…"
load_classpath
java -cp "$JPLAG_CP" "$MAIN_CLASS" --help >/dev/null

echo
echo "Build complete."
echo "  classpath -> $CLASSPATH_FILE"
echo "Next:"
echo "  scripts/build-database.sh  <docs-dir>  <index-dir>     # build the searchable corpus"
echo "  scripts/run-plagiarism.sh  <query-dir> <index-dir> <results-dir>"
