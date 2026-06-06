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
# Clear logcat BEFORE install/launch so we don't pick up stale lines from the
# snapshot or a previous run.
adb logcat -c
adb install -r "${PATCHED}"
# MainActivity.onCreate calls formatTicket and logs the result under tag
# RosettaVictim; with the module active it reads HOOKED(ticket:T-123).
adb shell am start -n com.example.victim/.MainActivity

# Poll until the RosettaVictim tag appears (proves onCreate ran) or time out.
echo "Waiting for RosettaVictim tag (up to 60 s) ..."
deadline=$(( $(date +%s) + 60 ))
while true; do
    if adb logcat -d -s RosettaVictim:I | grep -qF 'RosettaVictim'; then
        break
    fi
    if [ "$(date +%s)" -ge "${deadline}" ]; then
        echo "TIMEOUT: RosettaVictim tag never appeared — app may have crashed." >&2
        dump_diagnostics
        exit 1
    fi
    sleep 2
done

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
