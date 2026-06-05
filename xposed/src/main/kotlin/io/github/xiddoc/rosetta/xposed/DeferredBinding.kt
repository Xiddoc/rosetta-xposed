/*
 * Deferred binding (RFC 0001 Decision 2, "Deferred binding").
 *
 * Name *resolution* is always available — it's data. But the *hook* must wait
 * until the declaring obfuscated class is loadable. For packed / hardened apps
 * that load dex late, the real class loader isn't the one present at module
 * init; the target class appears only after the app's own loader runs.
 *
 * FLOW. [whenClassAvailable] resolves [realClass] to its obfuscated FQN and
 * probes loadability through the SAME C1-guarded chokepoint every other target
 * uses ([RosettaXposed.probeClassLoadable] → [TargetLoader.probeLoadable] →
 * [TargetGuard.assertAllowed]):
 *
 *   - Denied target (e.g. a map pointing `RealClient` at `java.lang.Runtime`):
 *     [io.github.xiddoc.rosetta.core.TargetPolicyException] is thrown BEFORE
 *     any `Class.forName`. The probe never loads a forbidden class — this is
 *     the M1 lesson (a health-check / probe must never become a guard bypass).
 *   - Already loadable: [onAvailable] runs synchronously, no watcher is
 *     scheduled, and a no-op [Registration] is returned.
 *   - Not yet loadable: a [ClassAvailabilityWatcher] is registered. On each
 *     signal we RE-PROBE through the guarded path (so a spurious early signal
 *     is harmless); the first probe that succeeds fires [onAvailable] EXACTLY
 *     ONCE (atomic run-once flag) and auto-cancels the watch.
 *     [Registration.cancel] before the class loads prevents the callback.
 *
 * Pure-JVM and framework-agnostic: no compile dependency on Xposed/libxposed.
 * The framework event (a `DexClassLoader` hook, libxposed
 * `onPackageReady().getClassLoader()`) is bridged in by the consuming module
 * via a [CallbackWatcher] it owns.
 */
package io.github.xiddoc.rosetta.xposed

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

public object DeferredBinding {
    /**
     * Run [onAvailable] with [rosetta] once [realClass]'s declaring obfuscated
     * class becomes loadable.
     *
     * If the class is already loadable, [onAvailable] runs synchronously and a
     * no-op [Registration] is returned. Otherwise [watcher] is registered and
     * [onAvailable] runs exactly once, after the first signal whose re-probe
     * confirms the class is loadable; the returned [Registration] cancels the
     * pending callback if invoked before that happens.
     *
     * @param rosetta the binding whose backend + guarded loader the probe goes
     *   through; [onAvailable] receives this same instance.
     * @param realClass the real (unobfuscated) class name to defer on.
     * @param watcher the source of "may now be loadable" signals.
     * @param onAvailable the callback to fire exactly once when loadable.
     * @throws io.github.xiddoc.rosetta.core.TargetPolicyException if the map
     *   points [realClass] at a denied/reserved obfuscated target — thrown
     *   before any class load, on the initial probe (the M1 lesson).
     */
    public fun whenClassAvailable(
        rosetta: RosettaXposed,
        realClass: String,
        watcher: ClassAvailabilityWatcher,
        onAvailable: (RosettaXposed) -> Unit,
    ): Registration {
        // Initial guarded probe. probeClassLoadable resolves realClass to its
        // obfuscated FQN exactly ONCE: it returns the obfName when the class is
        // already loadable, null when it is not yet present, and throws
        // TargetPolicyException BEFORE any Class.forName for a denied target
        // (the M1 lesson — guard fires before load, never after).
        val initialObfName = rosetta.probeClassLoadable(realClass)
        if (initialObfName != null) {
            onAvailable(rosetta)
            return NO_OP_REGISTRATION
        }

        // Not yet loadable. Obtain the obfuscated name for the watcher
        // registration — pure data lookup, no load attempted (the guard already
        // ran inside probeClassLoadable above and did not throw, so this is
        // a safe second resolveClass call at setup time only, not per-signal).
        val obfName = rosetta.resolveObfName(realClass)

        val fired = AtomicBoolean(false)
        // The watcher's own registration, used by the firing signal to
        // auto-cancel the watch. Initialised to a no-op so that if a watcher
        // ever invokes onSignal synchronously inside await() (before this is
        // assigned), the auto-cancel is simply a no-op — await() has not
        // returned anything to cancel yet, and the run-once flag still holds.
        val watcherReg = AtomicReference(NO_OP_REGISTRATION)

        val onSignal = {
            // Re-probe through the guarded path on every signal (single
            // backend.resolveClass call per signal — obfName is already known).
            // probeClassLoadable returns null for a spurious early signal
            // (harmless — the probe is still the chokepoint), and compareAndSet
            // wins exactly once even if signals overlap, so onAvailable runs at
            // most once and the watch then auto-cancels.
            if (rosetta.probeClassLoadable(realClass) != null && fired.compareAndSet(false, true)) {
                watcherReg.get().cancel()
                onAvailable(rosetta)
            }
        }

        watcherReg.set(watcher.await(obfName, onSignal))

        // Coalesced-fire cleanup: if onSignal fired SYNCHRONOUSLY inside
        // await() (e.g. CallbackWatcher's coalesced path), fired is already
        // true but watcherReg held a no-op at that moment, so the auto-cancel
        // inside onSignal was a no-op and the listener is still in the bucket.
        // Now that watcherReg holds the real Registration, cancel it to prune
        // the dead listener — a subsequent signalLoadable(same name) must not
        // re-invoke it.
        if (fired.get()) {
            watcherReg.get().cancel()
            return NO_OP_REGISTRATION
        }

        // The user-facing registration: cancelling before load both stops the
        // watcher and trips the run-once flag so a later signal cannot fire.
        return Registration {
            fired.set(true)
            watcherReg.get().cancel()
        }
    }
}
