#!/usr/bin/env bash
#
# build-libdexkit.sh — reproducibly build a host-native (Linux x86_64, glibc)
# libdexkit.so from the LuckyPray/DexKit C++ source, so a plain headless JVM
# can System.load(...) it and use org.luckypray.dexkit.DexKitBridge.
#
# The published Maven artifact (org.luckypray:dexkit:2.2.0) ships ONLY
# Android/bionic natives, so the host-native library must be built from source.
#
# This mirrors DexKit's own host build pipeline (the :dexkit-dev `cmakeBuild`
# Gradle task, which configures dexkit/src/main/cpp/CMakeLists.txt for the host
# machine with the ninja generator and -O3/Release). We invoke cmake+ninja
# directly to avoid pulling in the Android :demo Gradle module.
#
# Output is copied to:
#   dexkit/src/test/resources/native/linux-x86_64/libdexkit.so
#
# Usage:
#   tools/dexkit-native/build-libdexkit.sh [DEXKIT_VERSION]
#
# Requirements (Debian/Ubuntu): cmake, ninja-build, g++, git, a JDK (for JNI
# headers), and zlib1g-dev. Install missing ones with:
#   apt-get install -y cmake ninja-build g++ git zlib1g-dev default-jdk
#
set -euo pipefail

# ---- Parameters -------------------------------------------------------------
DEXKIT_VERSION="${1:-2.2.0}"          # matches Maven org.luckypray:dexkit:<ver>
DEXKIT_REPO="https://github.com/LuckyPray/DexKit"

# Resolve repo root from this script's location.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEST_DIR="$REPO_ROOT/dexkit/src/test/resources/native/linux-x86_64"
DEST_SO="$DEST_DIR/libdexkit.so"

SRC_DIR="${DEXKIT_SRC_DIR:-/tmp/dexkit-src}"   # clone lives OUTSIDE the repo
BUILD_DIR="${DEXKIT_BUILD_DIR:-/tmp/dexkit-build}"

# ---- Locate a JDK for JNI headers ------------------------------------------
if [ -z "${JAVA_HOME:-}" ]; then
    if command -v javac >/dev/null 2>&1; then
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    else
        echo "ERROR: JAVA_HOME unset and javac not found. Install a JDK." >&2
        exit 1
    fi
fi
export JAVA_HOME
echo "Using JAVA_HOME=$JAVA_HOME"
test -f "$JAVA_HOME/include/jni.h"        || { echo "ERROR: missing $JAVA_HOME/include/jni.h" >&2; exit 1; }
test -f "$JAVA_HOME/include/linux/jni_md.h" || { echo "ERROR: missing $JAVA_HOME/include/linux/jni_md.h" >&2; exit 1; }

# ---- Clone (with submodules) at the matching tag ----------------------------
if [ ! -d "$SRC_DIR/.git" ]; then
    echo "Cloning $DEXKIT_REPO -> $SRC_DIR ..."
    git clone --recursive "$DEXKIT_REPO" "$SRC_DIR"
fi

cd "$SRC_DIR"
# Try "<ver>" then "v<ver>".
if git rev-parse -q --verify "refs/tags/$DEXKIT_VERSION" >/dev/null; then
    TAG="$DEXKIT_VERSION"
elif git rev-parse -q --verify "refs/tags/v$DEXKIT_VERSION" >/dev/null; then
    TAG="v$DEXKIT_VERSION"
else
    echo "ERROR: tag $DEXKIT_VERSION / v$DEXKIT_VERSION not found." >&2
    echo "Available tags:" >&2
    git tag | sort -V | tail -20 >&2
    exit 1
fi
echo "Checking out tag $TAG ..."
git checkout -q "$TAG"
git submodule update --init --recursive
echo "Building DexKit commit: $(git rev-parse HEAD)"

# ---- Configure + build (host, Release) --------------------------------------
# Flags mirror :dexkit-dev's cmakeBuild task.
rm -rf "$BUILD_DIR"
cmake -S "$SRC_DIR/dexkit/src/main/cpp" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG" \
    -DCMAKE_C_FLAGS_RELEASE="-O3 -DNDEBUG" \
    -DDEXKIT_ENABLE_INTERNAL_METRICS=ON \
    -DDEXKIT_ENABLE_INTERNAL_METRICS_API=ON \
    -DJAVA_HOME="$JAVA_HOME"

cmake --build "$BUILD_DIR" -- -v 0 || ninja -C "$BUILD_DIR"

# ---- Copy artifact into the test resources ----------------------------------
mkdir -p "$DEST_DIR"
cp "$BUILD_DIR/libdexkit.so" "$DEST_SO"
echo "Copied -> $DEST_SO"

# ---- Strip -----------------------------------------------------------------
# Drop debug + local symbols to shrink the built binary. `--strip-unneeded`
# preserves the dynamic symbol table (.dynsym), so the JNI exports DexKit needs
# (Java_org_luckypray_dexkit_*) survive — verified below.
echo "Stripping (--strip-unneeded; keeps .dynsym JNI exports) ..."
strip --strip-unneeded "$DEST_SO"

# ---- Verify -----------------------------------------------------------------
echo "==== file ===="
file "$DEST_SO"
echo "==== NEEDED ===="
readelf -d "$DEST_SO" | grep NEEDED
echo "==== JNI exports (sample) ===="
nm -D "$DEST_SO" | grep -i 'Java_org_luckypray_dexkit' | head -5
echo "==== JNI export count (expect 39 for DexKit 2.2.0) ===="
nm -D "$DEST_SO" | grep -ic 'Java_org_luckypray_dexkit'
echo "==== sha256 (printed for verification; the .so is NOT committed) ===="
sha256sum "$DEST_SO"

echo "Done."
