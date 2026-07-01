#!/usr/bin/env bash
#
# embed-dexkit-native.sh — make on-device DexKit self-healing work under
# NON-ROOT LSPatch by embedding `libdexkit.so` into an LSPatch-patched host APK.
#
# WHY THIS EXISTS
# ---------------
# A Rosetta module's self-heal path builds a live DexKit index, which needs
# DexKit's native `libdexkit.so` loaded into the HOST app's process. Under
# rooted LSPosed that is automatic (the module is an installed app, so its `.so`
# sits in a real, exec-allowed `nativeLibraryDir`). Under non-root LSPatch the
# module is NOT installed — it is an APK embedded as a host asset — so there is
# no `nativeLibraryDir` for it, and:
#
#   * `System.loadLibrary("dexkit")` misses (nothing on the search path), and
#   * extracting the `.so` to a writable dir and `System.load`-ing it is BLOCKED
#     on stock Android 10+: a process targeting API 29+ is SELinux-neverallowed
#     from executing ANY `app_data_file`.
#
# The one route that works — and is exactly how LSPatch loads its own
# `liblspatch.so` — is mapping the `.so` DIRECTLY out of an INSTALLED APK via
# bionic's `apk!/entry` linker form. Installed APKs under `/data/app` carry the
# exec-allowed `apk_data_file` SELinux label, so this sidesteps W^X. That means
# `libdexkit.so` must physically live inside an installed APK — i.e. the patched
# HOST APK. This script puts it there.
#
# It injects `lib/arm64-v8a/libdexkit.so` into the patched APK and page-aligns
# it, so BOTH runtime load paths in `NativeLibraryLoadPlan` are satisfied on a
# real (arm64) device:
#   1. the ordinary `System.loadLibrary("dexkit")` (the installer now sees a
#      normal native lib in the host APK), and
#   2. the `System.load("<hostApk>!/lib/arm64-v8a/libdexkit.so")` fallback.
#
# It does NOT touch x86/x86_64 (LSPatch warns that arm libs under `lib/` crash
# the x86 native bridge on emulators; real devices are arm64). See
# docs/reference/lspatch-non-root.md.
#
# USAGE
#   tools/lspatch/embed-dexkit-native.sh <patched-host.apk> <source> [out.apk]
#
#     <patched-host.apk>  an APK already produced by LSPatch (module embedded).
#     <source>            where to get arm64-v8a/libdexkit.so from — either:
#                           * a `libdexkit.so` file, or
#                           * an APK that contains lib/arm64-v8a/libdexkit.so
#                             (e.g. the built TickPatch module APK).
#     [out.apk]           output path (default: <patched-host>-dexkit.apk).
#
# The result is zipaligned and signed with a debug keystore (auto-created at
# ~/.android/debug.keystore if absent), so it installs on a normal device.
# Override signing with a real keystore via the KS_* env vars below.
#
# REQUIREMENTS: an Android SDK build-tools on PATH or ANDROID_HOME/ANDROID_SDK_ROOT
# (for `zipalign` + `apksigner`), plus `zip`/`unzip` and a JDK (`keytool`).
#
# STATUS: the mechanism is grounded in LSPatch's own loader; end-to-end on a
# physical non-root device is the one thing still to confirm — do that before
# treating non-root self-heal as fully proven. The module degrades fail-soft if
# the native still cannot load (self-heal simply stays unavailable).

set -euo pipefail

die() { echo "error: $*" >&2; exit 1; }

readonly ABI="arm64-v8a"
readonly SO_NAME="libdexkit.so"
readonly ENTRY="lib/${ABI}/${SO_NAME}"

[[ $# -ge 2 ]] || die "usage: $0 <patched-host.apk> <source(.so|.apk)> [out.apk]"
PATCHED_APK="$1"
SOURCE="$2"
OUT_APK="${3:-${PATCHED_APK%.apk}-dexkit.apk}"

[[ -f "$PATCHED_APK" ]] || die "patched APK not found: $PATCHED_APK"
[[ -f "$SOURCE" ]] || die "source not found: $SOURCE"

# --- Locate SDK build-tools (zipalign + apksigner) -------------------------
find_build_tool() {
  local tool="$1"
  if command -v "$tool" >/dev/null 2>&1; then command -v "$tool"; return; fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [[ -n "$sdk" && -d "$sdk/build-tools" ]] || return 1
  # Newest build-tools version wins.
  local hit
  hit="$(find "$sdk/build-tools" -maxdepth 2 -name "$tool" -type f 2>/dev/null | sort -V | tail -1)"
  [[ -n "$hit" ]] && echo "$hit"
}

ZIPALIGN="$(find_build_tool zipalign)" || die "zipalign not found (set ANDROID_HOME to an SDK with build-tools)"
APKSIGNER="$(find_build_tool apksigner)" || die "apksigner not found (set ANDROID_HOME to an SDK with build-tools)"
command -v zip >/dev/null 2>&1 || die "zip is required"
command -v unzip >/dev/null 2>&1 || die "unzip is required"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# --- Resolve the arm64 libdexkit.so from <source> --------------------------
# Lay it out UNDER $WORK at the exact archive path (lib/<abi>/libdexkit.so) so
# `zip` (run from $WORK) stores it at that path inside the APK.
SO_DIR="$WORK/lib/${ABI}"
mkdir -p "$SO_DIR"
case "$SOURCE" in
  *.so)
    cp "$SOURCE" "$SO_DIR/$SO_NAME" ;;
  *)
    # Treat as an APK/zip; pull lib/arm64-v8a/libdexkit.so out of it.
    unzip -o -j "$SOURCE" "$ENTRY" -d "$SO_DIR" >/dev/null 2>&1 \
      || die "no $ENTRY inside $SOURCE (is it an arm64 module APK?)"
    ;;
esac
[[ -f "$SO_DIR/$SO_NAME" ]] || die "could not obtain $SO_NAME"
echo "• source native: $(du -h "$SO_DIR/$SO_NAME" | cut -f1) $SO_NAME"

# --- Inject the .so as a STORED (uncompressed) entry -----------------------
# The bionic `apk!/entry` loader (and extractNativeLibs=false direct mapping)
# require the entry to be uncompressed; page alignment is applied below.
STAGE="$WORK/stage.apk"
cp "$PATCHED_APK" "$STAGE"
( cd "$WORK" && zip -0 -X -q "$STAGE" "$ENTRY" )
echo "• injected $ENTRY (stored) into a copy of $(basename "$PATCHED_APK")"

# --- Page-align (must precede signing so the v2 signature stays valid) ------
ALIGNED="$WORK/aligned.apk"
# `-p` = page-align uncompressed .so entries; 4 = 4KiB legacy alignment (works
# for both 4K and 16K page kernels for our purposes on arm64).
"$ZIPALIGN" -p -f 4 "$STAGE" "$ALIGNED"
echo "• zipaligned"

# --- Sign (debug keystore by default; override via KS_* env) ---------------
KS_PATH="${KS_PATH:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"
KS_KEYPASS="${KS_KEYPASS:-$KS_PASS}"

if [[ ! -f "$KS_PATH" ]]; then
  echo "• no keystore at $KS_PATH — creating a debug keystore"
  mkdir -p "$(dirname "$KS_PATH")"
  keytool -genkeypair -v -keystore "$KS_PATH" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_KEYPASS" \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

"$APKSIGNER" sign \
  --ks "$KS_PATH" --ks-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_KEYPASS" \
  --out "$OUT_APK" "$ALIGNED"
"$APKSIGNER" verify "$OUT_APK" >/dev/null 2>&1 || die "signature verification failed"

echo "✓ wrote $OUT_APK"
echo
echo "Next: install it (replacing any prior patched install), then force-stop the"
echo "host so LSPatch reloads the module. On the self-heal path the log should show"
echo "  'TickPatch: DexKit native loaded via LOAD_LIBRARY dexkit'  (or LOAD_PATH …!/lib/…)."
echo "If instead you see 'DexKit native not loadable', capture the log and file it"
echo "against rosetta-xposed with the device's Android version + ABI."
