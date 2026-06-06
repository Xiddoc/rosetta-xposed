#!/usr/bin/env bash
#
# E2E hook assertion, run on the emulator by the android-e2e workflow.
#
# Why a committed script instead of an inline `script:` block:
# reactivecircus/android-emulator-runner executes the workflow `script` input
# LINE BY LINE (each line is its own `sh -c "<line>"` under dash) — so multi-line
# shell constructs (while/do/done, if/then/fi) break with
# `Syntax error: end of file unexpected (expecting "done")` and shell variables
# do not persist between lines. Calling this file as a single line sidesteps all
# of that: bash reads the whole file at once, so loops, variables, and pipefail
# work normally.
#
# Contract: the workflow exports PATCHED (path to the LSPatch-patched victim APK,
# relative to the repo root) into the environment before invoking us.
set -euo pipefail

# On any failure, surface what the LSPatch loader / Xposed bridge / the app did,
# so a non-firing hook (vs a crash) is diagnosable straight from the job log.
dump_diagnostics() {
    # Save the WHOLE buffer as an artifact — the crash happens early and scrolls
    # out of any tail, and ART logs the real missing class as a separate
    # verifier line (not in the swallowed "no stack trace available" exception).
    local full="${GITHUB_WORKSPACE:-.}/e2e-logcat.txt"
    adb logcat -d > "${full}" 2>/dev/null || true
    echo "Saved full logcat to ${full} ($(wc -l < "${full}" 2>/dev/null || echo 0) lines)" >&2

    echo "----- class-resolution / verifier failures (the real missing class) -----" >&2
    grep -inE 'could not find class|could not find method|noclassdeffound|classnotfound|rejecting|failed to resolve|unresolved|verifyerror|incompatibleclasschange' \
        "${full}" 2>/dev/null | tail -100 >&2 || true

    echo "----- AndroidRuntime (full crash + root cause) -----" >&2
    adb logcat -d -s AndroidRuntime 2>/dev/null | tail -120 >&2 || true

    echo "----- LSPatch / Xposed / rosetta loader lines -----" >&2
    grep -iE 'lspatch|lsposed|xposed|rosetta' "${full}" 2>/dev/null | tail -150 >&2 || true
}

cd "${GITHUB_WORKSPACE:-$(pwd)}"
echo "Using patched APK: ${PATCHED}"

adb wait-for-device

# The API 34 emulator enforces the hidden-API blacklist
# (dex2oat runs with -Xhidden-api-policy:enabled). Modules loaded by LSPatch
# touch non-SDK framework members during class init, and a blocked access
# surfaces as the misleading "NoClassDefFoundError: Class not found using the
# boot class loader" (ART hides the class rather than throwing access-denied).
# Relax the policy device-wide — this is a throwaway test emulator, and it is
# the on-device equivalent of what an LSPosed/Magisk host would grant a module.
adb shell settings put global hidden_api_policy 1 || true
adb shell settings put global hidden_api_policy_pre_p_apps 1 || true
adb shell settings put global hidden_api_policy_p_apps 1 || true

# Wait up to ${2:-60}s for the RosettaVictim tag to appear; print it. Returns
# 0 if seen, 1 on timeout. $1 is a human label for logging.
await_rosetta_tag() {
    label="$1"; timeout="${2:-60}"
    echo "[$label] waiting for RosettaVictim tag (up to ${timeout}s) ..."
    deadline=$(( $(date +%s) + timeout ))
    while true; do
        if adb logcat -d -s RosettaVictim:I | grep -qF 'RosettaVictim'; then
            return 0
        fi
        [ "$(date +%s)" -ge "${deadline}" ] && return 1
        sleep 2
    done
}

# --- Control: bare LSPatch (no module) should boot and log the UNHOOKED line.
# This is a harness sanity check — it proves the emulator + LSPatch loader work
# on this victim before we judge the module. Diagnostic only (never fails the
# job); its result just localizes any later crash.
if [ -n "${PATCHED_CONTROL:-}" ]; then
    echo "===== CONTROL: bare LSPatch, no module ====="
    adb logcat -c
    adb install -r "${PATCHED_CONTROL}"
    adb shell am start -n com.example.victim/.MainActivity
    if await_rosetta_tag control 45; then
        echo "CONTROL result:"; adb logcat -d -s RosettaVictim:I
        echo "CONTROL: bare LSPatch app ran (expected unhooked ticket:T-123)."
    else
        echo "CONTROL: bare LSPatch app did NOT log RosettaVictim — LSPatch itself" >&2
        echo "         fails to load on this emulator (not a module problem)." >&2
        dump_diagnostics
    fi
    adb uninstall com.example.victim >/dev/null 2>&1 || true
fi

echo "===== ASSERTION: module embedded, expect HOOKED(ticket:T-123) ====="
# Clear logcat BEFORE install/launch so we don't pick up stale lines from the
# control run / snapshot.
adb logcat -c
adb install -r "${PATCHED}"
# MainActivity.onCreate calls formatTicket and logs the result under tag
# RosettaVictim; with the module active it reads HOOKED(ticket:T-123).
adb shell am start -n com.example.victim/.MainActivity

if ! await_rosetta_tag assertion 60; then
    echo "TIMEOUT: RosettaVictim tag never appeared — app may have crashed." >&2
    dump_diagnostics
    exit 1
fi

# Dump the RosettaVictim lines once for the assertion + the log.
LOGCAT_DUMP="$(adb logcat -d -s RosettaVictim:I)"
echo "----- RosettaVictim logs -----"
echo "${LOGCAT_DUMP}"

if echo "${LOGCAT_DUMP}" | grep -qF 'HOOKED(ticket:T-123)'; then
    echo "E2E PASS: Rosetta-resolved hook fired (HOOKED(ticket:T-123))."
else
    echo "E2E FAIL: HOOKED(ticket:T-123) not observed (unhooked would be 'ticket:T-123')." >&2
    dump_diagnostics
    exit 1
fi
