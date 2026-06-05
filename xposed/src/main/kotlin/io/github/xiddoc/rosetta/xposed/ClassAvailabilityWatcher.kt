/*
 * Class-availability watchers (RFC 0001 Decision 2, "Deferred binding").
 *
 * For packed / hardened apps the declaring obfuscated class is not loadable
 * at module init — the app's own loader (a late `DexClassLoader`) brings it in
 * only after some app code runs. [DeferredBinding] needs a way to learn *when*
 * a given obfuscated class first becomes loadable; that "when" is exactly what
 * a [ClassAvailabilityWatcher] supplies.
 *
 * Two watchers ship:
 *
 *   - [ClassLoaderProbeWatcher] — actively polls the class loader on an
 *     INJECTED scheduling seam ([Ticker]). The seam (not a hardcoded thread /
 *     sleep) is what lets tests drive the poll deterministically.
 *   - [CallbackWatcher] — passive: the consuming Xposed module fires it from
 *     its own `DexClassLoader` hook or libxposed `onPackageReady()
 *     .getClassLoader()`. This is how the real framework integration plugs in
 *     WITHOUT `:xposed` taking a compile dependency on Xposed.
 *
 * IMPORTANT: a watcher only *signals* that a name might now be loadable.
 * [DeferredBinding] re-probes through the guarded load chokepoint on every
 * signal, so a watcher firing early (spurious) is harmless — it never bypasses
 * the C1 namespace guard, and it never fires the user callback before the
 * class is genuinely loadable.
 */
package io.github.xiddoc.rosetta.xposed

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A handle to an in-flight deferred registration. [cancel] detaches the
 * pending callback so it never fires; calling it after the callback already
 * ran (or after a previous cancel) is a harmless no-op.
 */
public fun interface Registration {
    /** Detach the pending callback. Idempotent and safe after firing. */
    public fun cancel()
}

/** A [Registration] that does nothing — returned when no waiting was needed. */
internal val NO_OP_REGISTRATION: Registration = Registration { /* nothing to cancel */ }

/**
 * Learns *when* an obfuscated class first becomes loadable.
 *
 * Implementations call [onLoadable] (possibly more than once, possibly
 * spuriously) when they believe [obfClassName] may now be present; the caller
 * ([DeferredBinding]) re-probes on each call and is responsible for run-once
 * semantics. They return a [Registration] the caller can use to stop waiting.
 */
public fun interface ClassAvailabilityWatcher {
    /**
     * Begin watching for [obfClassName]; invoke [onLoadable] when it may have
     * become loadable. Returns a [Registration] to cancel the watch.
     */
    public fun await(
        obfClassName: String,
        onLoadable: () -> Unit,
    ): Registration
}

/**
 * The scheduling seam for [ClassLoaderProbeWatcher]. An implementation runs
 * [task] repeatedly at its own cadence until the returned [Registration] is
 * cancelled. Production code can back this with a `ScheduledExecutorService`
 * or an Xposed/Handler post; tests drive it synchronously so there are no real
 * threads or sleeps.
 */
public fun interface Ticker {
    /** Schedule [task] to run on a cadence; cancel via the returned handle. */
    public fun schedule(task: () -> Unit): Registration
}

/**
 * Actively polls a [ClassLoader] for [obfClassName] on an injected [Ticker].
 *
 * It does NOT load the class itself — loading must go through the guarded
 * chokepoint in [DeferredBinding]. It merely re-runs the caller's
 * `onLoadable` on each tick; the caller re-probes (guarded) and decides. The
 * watch auto-cancels its ticker once the caller signals completion by
 * cancelling the [Registration] this watcher returned.
 *
 * @property ticker the scheduling seam (deterministic in tests).
 */
public class ClassLoaderProbeWatcher(
    private val ticker: Ticker,
) : ClassAvailabilityWatcher {
    override fun await(
        obfClassName: String,
        onLoadable: () -> Unit,
    ): Registration {
        // Each tick simply re-notifies the caller, which re-probes through the
        // guarded path. The ticker handle IS the registration.
        return ticker.schedule(onLoadable)
    }
}

/**
 * A passive watcher the consuming module fires by hand. The module hooks the
 * app's `DexClassLoader` (or reads libxposed's `onPackageReady()
 * .getClassLoader()`) and calls [signalLoadable] with the obfuscated name once
 * it sees the dex arrive — bridging the framework event into Rosetta WITHOUT
 * `:xposed` depending on Xposed at compile time.
 *
 * Signals are coalesced per obfuscated name: registering after a signal has
 * already arrived for that name fires immediately (the dex may have loaded
 * before the deferral was set up).
 */
public class CallbackWatcher : ClassAvailabilityWatcher {
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<() -> Unit>>()
    private val alreadySignalled = ConcurrentHashMap.newKeySet<String>()

    override fun await(
        obfClassName: String,
        onLoadable: () -> Unit,
    ): Registration {
        val bucket = listeners.computeIfAbsent(obfClassName) { CopyOnWriteArrayList() }
        bucket.add(onLoadable)
        // Coalesce: if the dex already arrived, notify right away.
        if (alreadySignalled.contains(obfClassName)) onLoadable()
        return Registration { bucket.remove(onLoadable) }
    }

    /**
     * Notify every current waiter for [obfName] that its class may now be
     * loadable, and remember the signal so a later [await] for the same name
     * fires immediately.
     */
    public fun signalLoadable(obfName: String) {
        alreadySignalled.add(obfName)
        listeners[obfName]?.forEach { it() }
    }
}
