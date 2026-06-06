#!/usr/bin/env bash
# setup-android-sdk.sh — provision a headless Android SDK for building the
# examples/android APKs (the victim app + the LSPosed module).
#
# NOTE: This script is Linux-only. The cmdline-tools URL below is the Linux
# build (commandlinetools-linux-*). It will not work on macOS or Windows.
#
# WHY THIS EXISTS
#   The Rosetta JVM story needs NO Android SDK: the resolver/maps build on a
#   plain JVM, and `examples/r8` obfuscates with standalone R8 (a Maven jar).
#   But assembling the actual APKs under examples/android DOES need the Android
#   Gradle Plugin + SDK (android.jar, aapt2, d8/dexing, build-tools). Cloud /
#   web agent sessions start WITHOUT an SDK, so this script installs one.
#
#   GitHub-hosted `ubuntu-latest` runners already ship the SDK preinstalled, so
#   CI usually does NOT need this — it's for fresh agent/dev environments.
#
# WHAT IT DOES (idempotent — safe to re-run; skips work already done)
#   - downloads the command-line tools into $ANDROID_SDK_ROOT/cmdline-tools/latest
#   - accepts SDK licenses
#   - installs platform-tools, platforms;android-34, build-tools;34.0.0
#     (match compileSdk/targetSdk in examples/android/*/build.gradle.kts)
#
# REQUIREMENTS: a JDK (17+; tested on 21) and network access to
#   dl.google.com (cmdline-tools) + Google Maven (AGP, resolved by Gradle).
#
# USAGE
#   ./scripts/setup-android-sdk.sh
#   # then, from the repo root:
#   export ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk"
#   ./gradlew -p examples/android :victim:assembleDebug :module:assembleDebug
#
# Override the install location with ANDROID_SDK_ROOT before running.
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
# Pinned command-line-tools build (update the number, URL, and SHA-256 together).
CMDLINE_TOOLS_VERSION="11076708"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
# SHA-256 of the above zip. Recompute when bumping CMDLINE_TOOLS_VERSION:
#   curl -fsSL "$CMDLINE_TOOLS_URL" | sha256sum
CMDLINE_TOOLS_SHA256="2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"
PACKAGES=("platform-tools" "platforms;android-34" "build-tools;34.0.0")

SM="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

echo "[setup-android-sdk] target SDK root: $SDK_ROOT"

if [ ! -x "$SM" ]; then
  echo "[setup-android-sdk] installing command-line tools ($CMDLINE_TOOLS_VERSION) ..."
  mkdir -p "$SDK_ROOT/cmdline-tools"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/cmdtools.zip" "$CMDLINE_TOOLS_URL"
  echo "$CMDLINE_TOOLS_SHA256  $tmp/cmdtools.zip" | sha256sum --check
  unzip -q -o "$tmp/cmdtools.zip" -d "$SDK_ROOT/cmdline-tools"
  # The zip extracts to .../cmdline-tools/cmdline-tools; sdkmanager expects it
  # under .../cmdline-tools/latest.
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
else
  echo "[setup-android-sdk] command-line tools already present, skipping download."
fi

echo "[setup-android-sdk] accepting licenses ..."
yes | "$SM" --sdk_root="$SDK_ROOT" --licenses 2>/dev/null || true

echo "[setup-android-sdk] installing: ${PACKAGES[*]}"
"$SM" --sdk_root="$SDK_ROOT" "${PACKAGES[@]}"

echo "[setup-android-sdk] done. Installed components:"
ls "$SDK_ROOT"
cat <<EOF

[setup-android-sdk] Next steps:
  export ANDROID_HOME="$SDK_ROOT" ANDROID_SDK_ROOT="$SDK_ROOT"
  ./gradlew -p examples/android :victim:assembleDebug :module:assembleDebug

(Or drop 'sdk.dir=$SDK_ROOT' into examples/android/local.properties — it is gitignored.)
EOF
