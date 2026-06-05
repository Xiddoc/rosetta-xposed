#!/usr/bin/env sh
#
# Cross-repo conformance-fixture sync guard.
#
# The resolver conformance fixtures are hand-vendored BYTE-IDENTICAL into
# two repos (RFC 0001 Decision 2 — "two resolver implementations, one
# conformance suite"):
#
#   rosetta-xposed: core/src/test/resources/conformance/   (AUTHORITATIVE)
#   rosetta-frida:  tests/conformance/fixtures/             (vendored copy)
#
# rosetta-xposed owns the canonical copy (see that directory's README.md).
# This script recomputes a SHA-256 manifest over the fixtures in THIS repo's
# conformance directory and diffs it against the committed, shared manifest
# (`scripts/conformance-fixtures.sha256`). The SAME manifest file is carried
# byte-identical in BOTH repos, so if either repo's fixtures drift from the
# shared baseline, that repo's CI fails here — catching a one-sided edit that
# would silently fork the parity oracle.
#
# Usage:
#   scripts/check-conformance-fixtures.sh [FIXTURES_DIR]
# FIXTURES_DIR defaults to this repo's conformance directory.
#
# Regenerating the manifest after an INTENTIONAL fixture change (must be done
# in BOTH repos so the two manifests stay identical):
#   ( cd <fixtures-dir> && for f in $(ls | sort); do sha256sum "$f"; done ) \
#       > scripts/conformance-fixtures.sha256

set -eu

# Resolve the repo root from this script's location so it works from any CWD.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

DEFAULT_FIXTURES_DIR="$REPO_ROOT/core/src/test/resources/conformance"
FIXTURES_DIR="${1:-$DEFAULT_FIXTURES_DIR}"
MANIFEST="$SCRIPT_DIR/conformance-fixtures.sha256"

if [ ! -d "$FIXTURES_DIR" ]; then
    echo "error: fixtures dir not found: $FIXTURES_DIR" >&2
    exit 1
fi
if [ ! -f "$MANIFEST" ]; then
    echo "error: manifest not found: $MANIFEST" >&2
    exit 1
fi

# Compute the actual manifest (sorted by basename, hashing basenames so the
# manifest is path-agnostic across the two repos' different dir layouts).
ACTUAL=$(
    cd "$FIXTURES_DIR" || exit 1
    for f in $(ls | sort); do
        sha256sum "$f"
    done
)

EXPECTED=$(cat "$MANIFEST")

if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "::error::conformance fixtures in '$FIXTURES_DIR' do not match the shared manifest." >&2
    echo "The two repos' fixture copies have drifted (or the manifest is stale)." >&2
    echo "--- expected (scripts/conformance-fixtures.sha256) ---" >&2
    echo "$EXPECTED" >&2
    echo "--- actual ---" >&2
    echo "$ACTUAL" >&2
    echo "If the change is intentional, re-vendor BOTH repos byte-identical and" >&2
    echo "regenerate the manifest in BOTH (see the header of this script)." >&2
    exit 1
fi

echo "conformance fixtures match the shared manifest ($(echo "$EXPECTED" | wc -l | tr -d ' ') files)"
