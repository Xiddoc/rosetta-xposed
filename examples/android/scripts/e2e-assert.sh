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

# launch_and_wait <human-label>: clear logcat, cold-start MainActivity, and
# wait for the RosettaVictim (static) tag to appear. Exits the script on a
# timeout (the app crashed / never ran). Used before EACH assertion phase so
# every launch reads a clean buffer.
launch_and_wait() {
    label="$1"
    adb logcat -c
    adb shell am force-stop com.example.victim || true
    adb shell am start -n com.example.victim/.MainActivity
    if ! await_rosetta_tag "${label}" 60; then
        echo "TIMEOUT [${label}]: RosettaVictim tag never appeared — app may have crashed." >&2
        dump_diagnostics
        exit 1
    fi
}

echo "===== ASSERTION 1: static hook — expect HOOKED(ticket:T-123) ====="
# Clear logcat BEFORE install so we don't pick up stale lines from the control
# run / snapshot.
adb logcat -c
adb install -r "${PATCHED}"
# MainActivity.onCreate calls formatTicket and logs the result under tag
# RosettaVictim; with the module active it reads HOOKED(ticket:T-123).
launch_and_wait static

# Dump the RosettaVictim lines once for the assertion + the log.
LOGCAT_DUMP="$(adb logcat -d -s RosettaVictim:I)"
echo "----- RosettaVictim (static) logs -----"
echo "${LOGCAT_DUMP}"

if echo "${LOGCAT_DUMP}" | grep -qF 'HOOKED(ticket:T-123)'; then
    echo "E2E PASS (static): Rosetta-resolved hook fired (HOOKED(ticket:T-123))."
else
    echo "E2E FAIL (static): HOOKED(ticket:T-123) not observed (unhooked would be 'ticket:T-123')." >&2
    dump_diagnostics
    exit 1
fi

# -------------------------------------------------------------------------
# DYNAMIC path (rosetta-xposed#22): static miss → DexKit discovery → hook.
#
# AuditService (`c.d#e`) is DELIBERATELY absent from the bundled map, so the
# module resolves it by live DexKit discovery. The victim logs the (possibly
# hooked) result under RosettaVictimDyn; the module logs the resolve PATH
# (DISCOVERED / SERVED_FROM_CACHE / CACHE_INVALIDATED) under RosettaDiscovery.
#
# We assert on the FIRST module launch above (already booted): the hook fired
# AND it was a fresh DISCOVERED scan (the cache was just invalidated on first
# run). Then a SECOND launch must be SERVED_FROM_CACHE (no rescan). Then a
# version-bumped APK must CACHE_INVALIDATE and re-DISCOVER.
# -------------------------------------------------------------------------

# assert_marker <logcat-dump> <grep-pattern> <pass-msg> <fail-msg>: fixed-string
# grep; pass or dump+exit. Keeps the three dynamic phases uniform.
assert_marker() {
    dump="$1"; pattern="$2"; pass="$3"; fail="$4"
    if echo "${dump}" | grep -qF "${pattern}"; then
        echo "${pass}"
    else
        echo "${fail}" >&2
        dump_diagnostics
        exit 1
    fi
}

echo "===== ASSERTION 2: dynamic hook fired + fresh DISCOVERED scan ====="
DYN_DUMP="$(adb logcat -d -s RosettaVictimDyn:I)"
DISC_DUMP="$(adb logcat -d -s RosettaDiscovery:I)"
echo "----- RosettaVictimDyn logs -----"; echo "${DYN_DUMP}"
echo "----- RosettaDiscovery logs -----"; echo "${DISC_DUMP}"
assert_marker "${DYN_DUMP}" 'DHOOKED(' \
    "E2E PASS (dynamic): discovered hook fired (DHOOKED(...))." \
    "E2E FAIL (dynamic): DHOOKED(...) not observed — discovery did not resolve/hook AuditService."
assert_marker "${DISC_DUMP}" 'DISCOVERED com.example.victim.AuditService' \
    "E2E PASS (dynamic): first launch was a fresh DexKit DISCOVERED scan." \
    "E2E FAIL (dynamic): expected a DISCOVERED marker on the first launch."

echo "===== ASSERTION 3: relaunch is SERVED_FROM_CACHE (no rescan) ====="
# Same APK, same versionCode → the persistent cache from launch #1 is still
# valid, so the discovery must be served from it WITHOUT a DexKit scan.
launch_and_wait cache-hit
DISC_DUMP="$(adb logcat -d -s RosettaDiscovery:I)"
echo "----- RosettaDiscovery (relaunch) logs -----"; echo "${DISC_DUMP}"
assert_marker "${DISC_DUMP}" 'SERVED_FROM_CACHE com.example.victim.AuditService' \
    "E2E PASS (cache): relaunch served the discovery from the persistent cache." \
    "E2E FAIL (cache): expected SERVED_FROM_CACHE on relaunch (cache did not survive the restart)."
# A relaunch of the SAME build must NOT re-run a fresh scan.
if echo "${DISC_DUMP}" | grep -qF 'DISCOVERED com.example.victim.AuditService'; then
    echo "E2E FAIL (cache): a fresh DISCOVERED scan ran on relaunch — the cache was not used." >&2
    dump_diagnostics
    exit 1
fi
echo "E2E PASS (cache): no fresh discovery scan on relaunch."

# -------------------------------------------------------------------------
# ASSERTION 4: cache invalidation on a version bump. The workflow builds a
# SECOND victim APK with a bumped versionCode (patched into ${PATCHED_BUMPED}).
# Installing it over the first is an app update: the persistent cache's
# (app, version_code, signer) fingerprint changes, so the stale entry is
# dropped (CACHE_INVALIDATED) and the name is re-DISCOVERED. Skipped (not
# failed) if the workflow did not provide the bumped APK.
# -------------------------------------------------------------------------
if [ -n "${PATCHED_BUMPED:-}" ]; then
    echo "===== ASSERTION 4: version bump → CACHE_INVALIDATED → re-DISCOVERED ====="
    adb logcat -c
    # Update install (-r) over the existing package; the cache prefs survive the
    # update, but the fingerprint no longer matches, forcing invalidation.
    adb install -r "${PATCHED_BUMPED}"
    launch_and_wait invalidation
    DISC_DUMP="$(adb logcat -d -s RosettaDiscovery:I)"
    echo "----- RosettaDiscovery (post-bump) logs -----"; echo "${DISC_DUMP}"
    # Assert the GENUINE-update marker specifically: a bare CACHE_INVALIDATED
    # token also matches the first-run marker, so pin the (version-or-signer-change)
    # variant to prove this was an update, not a fresh install.
    assert_marker "${DISC_DUMP}" 'CACHE_INVALIDATED (version-or-signer-change)' \
        "E2E PASS (invalidation): version bump dropped the stale cache (CACHE_INVALIDATED)." \
        "E2E FAIL (invalidation): expected CACHE_INVALIDATED (version-or-signer-change) after the version bump."
    assert_marker "${DISC_DUMP}" 'DISCOVERED com.example.victim.AuditService' \
        "E2E PASS (invalidation): the name was re-DISCOVERED after invalidation." \
        "E2E FAIL (invalidation): expected a fresh DISCOVERED scan after invalidation."
else
    echo "NOTE: PATCHED_BUMPED not provided — skipping the version-bump invalidation assertion." >&2
fi

echo "E2E PASS: all static + dynamic (discovery / cache-hit / invalidation) assertions passed."
