#!/usr/bin/env bash
# build.sh — hermetic fixture DEX builder
#
# Reproduces dexkit/src/test/resources/dex/fixture.dex and fixture-mapping.json
# from scratch using only javac (JDK 17+ recommended; tested on JDK 21) and
# the standalone R8 jar (no Android SDK required).
#
# R8 version: 8.5.35
# Output: fixture.dex (StandardDex, magic dex\n035), fixture-mapping.json
#
# Usage:
#   cd tools/dex-fixture
#   ./build.sh                # build and copy artifacts to dexkit/src/test/resources/dex/
#
# The script is idempotent: re-running it re-downloads R8 only if the jar is absent,
# recompiles sources, re-obfuscates, and regenerates the JSON from the fresh mapping.txt.
#
# To refresh after changing Java sources or proguard rules:
#   1. Edit src/**/*.java, proguard-rules.pro, or seed.txt as needed.
#   2. Re-run ./build.sh.
#   3. Commit both the updated .dex and fixture-mapping.json.

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
R8_VERSION="8.5.35"
R8_URL="https://maven.google.com/com/android/tools/r8/${R8_VERSION}/r8-${R8_VERSION}.jar"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUT_RESOURCES="${REPO_ROOT}/dexkit/src/test/resources/dex"

WORK_DIR="${SCRIPT_DIR}/.build"
CLASSES_DIR="${WORK_DIR}/classes"
R8_JAR="${WORK_DIR}/r8.jar"
OUT_DIR="${WORK_DIR}/out"

# ---------------------------------------------------------------------------
# Setup
# ---------------------------------------------------------------------------
mkdir -p "${CLASSES_DIR}" "${OUT_DIR}"

# ---------------------------------------------------------------------------
# Step 1: Download R8 (skip if already present)
# ---------------------------------------------------------------------------
if [ ! -f "${R8_JAR}" ]; then
  echo "[build.sh] Downloading R8 ${R8_VERSION} ..."
  curl -L "${R8_URL}" -o "${R8_JAR}"
  echo "[build.sh] Downloaded $(du -h "${R8_JAR}" | cut -f1) to ${R8_JAR}"
else
  echo "[build.sh] R8 jar already present ($(du -h "${R8_JAR}" | cut -f1)), skipping download."
fi

# Verify R8 version
R8_REPORTED=$(java -cp "${R8_JAR}" com.android.tools.r8.R8 --version 2>&1 | head -1)
echo "[build.sh] ${R8_REPORTED}"

# ---------------------------------------------------------------------------
# Step 2: Compile Java sources
# ---------------------------------------------------------------------------
echo "[build.sh] Compiling sources ..."
rm -rf "${CLASSES_DIR:?}"/*
javac --release 11 \
  -d "${CLASSES_DIR}" \
  "${SCRIPT_DIR}/src/com/rosetta/dexfixture/AnchoredWidget.java" \
  "${SCRIPT_DIR}/src/com/rosetta/dexfixture/BaseHandler.java" \
  "${SCRIPT_DIR}/src/com/rosetta/dexfixture/NetworkHandler.java" \
  "${SCRIPT_DIR}/src/com/rosetta/dexfixture/RemoteStub.java" \
  "${SCRIPT_DIR}/src/com/rosetta/dexfixture/Main.java"

# ---------------------------------------------------------------------------
# Step 3: Run R8 (compile + shrink + obfuscate → DEX)
# ---------------------------------------------------------------------------
echo "[build.sh] Running R8 ${R8_VERSION} ..."
rm -f "${OUT_DIR}/classes.dex" "${OUT_DIR}/mapping.txt"

# proguard-rules.pro uses relative path for -applymapping seed.txt, so we
# run from SCRIPT_DIR.  -printmapping is injected via a small extra conf
# fragment so the path can be set to the correct OUT_DIR.
EXTRA_CONF="${WORK_DIR}/extra.pro"
echo "-printmapping ${OUT_DIR}/mapping.txt" > "${EXTRA_CONF}"

(
  cd "${SCRIPT_DIR}"
  java -cp "${R8_JAR}" com.android.tools.r8.R8 \
    --release \
    --min-api 21 \
    --lib "${JAVA_HOME}" \
    --output "${OUT_DIR}" \
    --pg-conf proguard-rules.pro \
    --pg-conf "${EXTRA_CONF}" \
    "${CLASSES_DIR}/com/rosetta/dexfixture/AnchoredWidget.class" \
    "${CLASSES_DIR}/com/rosetta/dexfixture/BaseHandler.class" \
    "${CLASSES_DIR}/com/rosetta/dexfixture/NetworkHandler.class" \
    "${CLASSES_DIR}/com/rosetta/dexfixture/RemoteStub.class" \
    "${CLASSES_DIR}/com/rosetta/dexfixture/Main.class"
)

# ---------------------------------------------------------------------------
# Step 4: Verify DEX magic bytes (must be StandardDex, not CompactDex)
# ---------------------------------------------------------------------------
MAGIC=$(od -A n -t x1 -N 8 "${OUT_DIR}/classes.dex" | tr -d ' \n')
echo "[build.sh] DEX magic bytes: ${MAGIC}"
# Expected: 6465780a30333500  (dex\n035\0)  or  6465780a30333900 (dex\n039\0)
if [[ "${MAGIC}" != 6465780a3033* ]]; then
  echo "[build.sh] ERROR: unexpected DEX magic; expected dex\\n03x." >&2
  exit 1
fi
echo "[build.sh] StandardDex confirmed."

# ---------------------------------------------------------------------------
# Step 5: Verify anchor strings survive in the DEX
# ---------------------------------------------------------------------------
echo "[build.sh] Verifying anchor strings ..."
if ! strings "${OUT_DIR}/classes.dex" | grep -q "rosetta-dexfixture-anchor-AnchoredWidget"; then
  echo "[build.sh] ERROR: AnchoredWidget anchor string missing from DEX." >&2
  exit 1
fi
if ! strings "${OUT_DIR}/classes.dex" | grep -q "com.rosetta.dexfixture.IRemote"; then
  echo "[build.sh] ERROR: RemoteStub DESCRIPTOR string missing from DEX." >&2
  exit 1
fi
echo "[build.sh] Anchor strings OK."

# ---------------------------------------------------------------------------
# Step 6: Parse mapping.txt and regenerate fixture-mapping.json
# ---------------------------------------------------------------------------
echo "[build.sh] Regenerating fixture-mapping.json from mapping.txt ..."

MAPPING_FILE="${OUT_DIR}/mapping.txt"

# Extract obfuscated names from mapping.txt using awk.
# mapping.txt format (non-comment lines):
#   <realClass> -> <obfClass>:
#   [    <member>]

get_class_obf() {
  local real="$1"
  awk -v cls="${real}" '
    /^[^#]/ && / -> / && /:$/ {
      split($0, a, " -> ");
      gsub(/:$/, "", a[2]);
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", a[1]);
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", a[2]);
      if (a[1] == cls) { print a[2]; exit }
    }
  ' "${MAPPING_FILE}"
}

# Get method obf name: look for member lines within a class block.
# R8 mapping method format: "    [N:N:]<type> <name>(<params>)[:N:N] -> <obf>"
# We match on the method name and parameter list, ignoring line-number decorations.
get_method_obf() {
  local real_class="$1"
  local method_name="$2"  # e.g. "process"
  local method_params="$3" # e.g. "java.lang.String"
  awk -v cls="${real_class}" -v mname="${method_name}" -v mparams="${method_params}" '
    /^[^#]/ && / -> / && /:$/ {
      in_class = ($0 ~ "^" cls " ->")
    }
    in_class && /^[[:space:]]/ && / -> / {
      line = $0
      gsub(/^[[:space:]]+/, "", line)
      # Strip leading line-number ranges like "2:2:"
      gsub(/^[0-9]+:[0-9]+:/, "", line)
      # Extract the part before " -> "
      n = split(line, a, " -> ")
      lhs = a[1]
      rhs = a[n]
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", rhs)
      # Strip trailing ":N:N" from lhs (inline position suffix)
      gsub(/:[0-9]+:[0-9]+$/, "", lhs)
      # lhs is now like "java.lang.String process(java.lang.String)"
      # Check if method name and params match
      if (lhs ~ ("^[^ ]+ " mname "\\(" mparams "\\)$")) { print rhs; exit }
    }
  ' "${MAPPING_FILE}"
}

OBF_ANCHORED=$(get_class_obf "com.rosetta.dexfixture.AnchoredWidget")
OBF_BASE=$(get_class_obf "com.rosetta.dexfixture.BaseHandler")
OBF_NETWORK=$(get_class_obf "com.rosetta.dexfixture.NetworkHandler")
OBF_REMOTE=$(get_class_obf "com.rosetta.dexfixture.RemoteStub")
OBF_PROCESS=$(get_method_obf "com.rosetta.dexfixture.NetworkHandler" "process" "java.lang.String")

echo "[build.sh] AnchoredWidget -> ${OBF_ANCHORED}"
echo "[build.sh] BaseHandler    -> ${OBF_BASE}"
echo "[build.sh] NetworkHandler -> ${OBF_NETWORK}  (process -> ${OBF_PROCESS})"
echo "[build.sh] RemoteStub     -> ${OBF_REMOTE}"

# Write JSON
cat > "${OUT_DIR}/fixture-mapping.json" <<JSONEOF
{
  "dexkitFixtureVersion": 1,
  "package": "com.rosetta.dexfixture",
  "classes": {
    "com.rosetta.dexfixture.AnchoredWidget": {
      "obfuscated": "${OBF_ANCHORED}",
      "anchors": ["rosetta-dexfixture-anchor-AnchoredWidget"]
    },
    "com.rosetta.dexfixture.BaseHandler": {
      "obfuscated": "${OBF_BASE}"
    },
    "com.rosetta.dexfixture.NetworkHandler": {
      "obfuscated": "${OBF_NETWORK}",
      "extends": "${OBF_BASE}",
      "methods": {
        "process": {
          "obfuscated": "${OBF_PROCESS}",
          "signature": "(Ljava/lang/String;)Ljava/lang/String;"
        }
      }
    },
    "com.rosetta.dexfixture.RemoteStub": {
      "obfuscated": "${OBF_REMOTE}",
      "aidlDescriptor": "com.rosetta.dexfixture.IRemote"
    }
  }
}
JSONEOF

echo "[build.sh] fixture-mapping.json written."

# ---------------------------------------------------------------------------
# Step 7: Copy artifacts into the repo
# ---------------------------------------------------------------------------
mkdir -p "${OUT_RESOURCES}"
cp "${OUT_DIR}/classes.dex"         "${OUT_RESOURCES}/fixture.dex"
cp "${OUT_DIR}/fixture-mapping.json" "${OUT_RESOURCES}/fixture-mapping.json"

echo "[build.sh] Artifacts copied to ${OUT_RESOURCES}/"
echo "[build.sh]   fixture.dex             ($(du -h "${OUT_RESOURCES}/fixture.dex" | cut -f1))"
echo "[build.sh]   fixture-mapping.json"
echo "[build.sh] Done. Commit both files."
