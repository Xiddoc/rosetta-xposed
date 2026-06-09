/*
 * DeferredBinding (RFC 0001 Decision 2, "Deferred binding"). These run on a
 * plain JVM with NO real threads or sleeps: a LateLoadingClassLoader fixture
 * stands in for a packed app's late dex (it throws ClassNotFound until
 * "released"), and a manual Ticker drives the probe watcher one tick at a time.
 *
 * Coverage:
 *   - already-loadable          → synchronous fire, no scheduling.
 *   - becomes-loadable-later     → single fire after signal (run-once counter).
 *   - spurious-signal-before-load→ re-probe fails, no fire; fires after release.
 *   - cancel-before-load         → never fires.
 *   - both watchers              → probe (deterministic Ticker) + direct callback.
 *   - denied/reserved obf target → TargetPolicyException, NEVER probe-loaded.
 *   - resolution while absent    → member bind throws BindException until release.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.TargetPolicyException
import io.github.xiddoc.rosetta.xposed.fixtures.LateLoadingClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeferredBindingTest {
    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"

    private val map =
        MapLoader.fromJson(
            """
            {
              "schema_version": 3,
              "app": "com.example.app",
              "version": "1.0.0",
              "version_code": 100,
              "classes": {
                "com.example.RealClient": {
                  "obfuscated": "$obf",
                  "methods": {
                    "single": { "obfuscated": "c", "signature": "(Ljava/lang/String;)Ljava/lang/String;" }
                  },
                  "fields": {}
                }
              }
            }
            """.trimIndent(),
        )

    private val policy = TargetPolicy(allow = listOf(obf))

    /** A Ticker that runs the scheduled task only when the test calls [tick]. */
    private class ManualTicker : Ticker {
        private var task: (() -> Unit)? = null
        var cancelled = false
            private set

        override fun schedule(task: () -> Unit): Registration {
            this.task = task
            return Registration { cancelled = true }
        }

        /** Run the scheduled task once (a no-op if cancelled or never scheduled). */
        fun tick() {
            if (!cancelled) task?.invoke()
        }
    }

    private fun rosettaOver(loader: ClassLoader) = RosettaXposed.fromMapUnverified(map, loader, policy)

    // ---- already-loadable → synchronous fire, no scheduling ------------------

    @Test
    fun `already-loadable fires synchronously and schedules nothing`() {
        val ticker = ManualTicker()
        val rosetta = rosettaOver(javaClass.classLoader)
        var fires = 0
        var got: RosettaXposed? = null

        val reg =
            DeferredBinding.whenClassAvailable(
                rosetta,
                "com.example.RealClient",
                ClassLoaderProbeWatcher(ticker),
            ) { r ->
                fires++
                got = r
            }

        assertEquals(1, fires)
        assertSame(rosetta, got)
        // No watcher was scheduled: the no-op registration is returned and the
        // ticker was never asked for a registration to cancel.
        assertSame(NO_OP_REGISTRATION, reg)
        reg.cancel() // exercise the no-op cancel path
        assertFalse(ticker.cancelled)
    }

    // ---- becomes-loadable-later → single fire after signal (run-once) --------

    @Test
    fun `becomes loadable later fires exactly once after release and signal`() {
        val late = LateLoadingClassLoader(javaClass.classLoader, setOf(obf))
        val rosetta = rosettaOver(late)
        val ticker = ManualTicker()
        var fires = 0

        DeferredBinding.whenClassAvailable(
            rosetta,
            "com.example.RealClient",
            ClassLoaderProbeWatcher(ticker),
        ) { fires++ }

        // Not loadable yet — nothing fired, a watcher was scheduled.
        assertEquals(0, fires)

        late.release()
        ticker.tick()
        assertEquals(1, fires)

        // Run-once: further ticks (even if the watcher fires again) do not refire,
        // and the watch auto-cancelled.
        assertTrue(ticker.cancelled)
        ticker.tick()
        assertEquals(1, fires)
    }

    // ---- spurious signal before loadable → re-probe fails, no fire -----------

    @Test
    fun `a spurious signal before the class loads does not fire`() {
        val late = LateLoadingClassLoader(javaClass.classLoader, setOf(obf))
        val rosetta = rosettaOver(late)
        val ticker = ManualTicker()
        var fires = 0

        DeferredBinding.whenClassAvailable(
            rosetta,
            "com.example.RealClient",
            ClassLoaderProbeWatcher(ticker),
        ) { fires++ }

        // Spurious tick while the class is still absent — re-probe fails.
        ticker.tick()
        assertEquals(0, fires)
        assertFalse(ticker.cancelled)

        // Now it really loads; the next signal fires (once).
        late.release()
        ticker.tick()
        assertEquals(1, fires)
    }

    // ---- cancellation before load → never fires ------------------------------

    @Test
    fun `cancellation before load prevents the callback`() {
        val late = LateLoadingClassLoader(javaClass.classLoader, setOf(obf))
        val rosetta = rosettaOver(late)
        val ticker = ManualTicker()
        var fires = 0

        val reg =
            DeferredBinding.whenClassAvailable(
                rosetta,
                "com.example.RealClient",
                ClassLoaderProbeWatcher(ticker),
            ) { fires++ }

        reg.cancel()
        assertTrue(ticker.cancelled)

        // Even if the class loads and a stray signal arrives, the run-once flag
        // (tripped by cancel) and the cancelled ticker keep it silent.
        late.release()
        ticker.tick()
        assertEquals(0, fires)
    }

    // ---- CallbackWatcher: direct fire + coalescing + cancel ------------------

    @Test
    fun `CallbackWatcher fires when the module signals the obf name`() {
        val late = LateLoadingClassLoader(javaClass.classLoader, setOf(obf))
        val rosetta = rosettaOver(late)
        val watcher = CallbackWatcher()
        var fires = 0

        DeferredBinding.whenClassAvailable(rosetta, "com.example.RealClient", watcher) { fires++ }
        assertEquals(0, fires)

        // A signal for an unrelated name does nothing.
        watcher.signalLoadable("some.other.Class")
        assertEquals(0, fires)

        // A signal before the class is loadable re-probes and stays silent.
        watcher.signalLoadable(obf)
        assertEquals(0, fires)

        // Once loadable, the signal fires exactly once.
        late.release()
        watcher.signalLoadable(obf)
        assertEquals(1, fires)
        watcher.signalLoadable(obf)
        assertEquals(1, fires)
    }

    @Test
    fun `CallbackWatcher coalesces a signal that arrived before await`() {
        // Class already loadable; the watcher was signalled before the deferral
        // was registered. await() should fire immediately on registration.
        val watcher = CallbackWatcher()
        watcher.signalLoadable(obf)

        var fired = false
        watcher.await(obf) { fired = true }
        assertTrue(fired)
    }

    @Test
    fun `coalesced DeferredBinding fire does not retain the listener after firing`() {
        // Regression test for the coalesced-fire listener leak:
        //   1. Signal arrived before whenClassAvailable → watcher fires
        //      onSignal synchronously INSIDE await(), at which point watcherReg
        //      is still NO_OP_REGISTRATION, so the auto-cancel inside onSignal
        //      is a no-op and the listener is still in the bucket.
        //   2. DeferredBinding must cancel the real Registration after await()
        //      returns to prune the dead listener from the bucket.
        //   3. A subsequent signalLoadable(same name) must NOT re-invoke the
        //      listener (it was fired exactly once and is now detached).
        //
        // Setup: class is NOT loadable at the initial probe, but becomes
        // loadable right inside the watcher's await() call (the class-becomes-
        // available race simulated deterministically via a custom watcher).
        val late = LateLoadingClassLoader(javaClass.classLoader, setOf(obf))
        val rosetta = rosettaOver(late)

        // A watcher that fires onLoadable() synchronously inside await(),
        // releasing the class first so the re-probe inside onSignal succeeds.
        // Captures the registration returned so later signalLoadable calls can
        // be simulated (the bucket-management pattern mirrors CallbackWatcher).
        val capturedCallbacks = mutableListOf<() -> Unit>()
        val syncCoalescingWatcher =
            ClassAvailabilityWatcher { _, onLoadable ->
                capturedCallbacks.add(onLoadable)
                late.release() // class becomes available before the sync fire
                onLoadable() // fire synchronously, watcherReg still NO_OP at this point
                Registration { capturedCallbacks.remove(onLoadable) }
            }

        var fires = 0
        DeferredBinding.whenClassAvailable(rosetta, "com.example.RealClient", syncCoalescingWatcher) {
            fires++
        }

        // Coalesced sync-fire should have delivered the callback exactly once.
        assertEquals(1, fires)

        // Simulate a "subsequent signal": invoke all captured callbacks (as
        // signalLoadable would). After the fix, the listener was pruned by the
        // Registration cancel, so capturedCallbacks is empty — no re-invocation.
        capturedCallbacks.forEach { it() }
        assertEquals(1, fires) // must still be 1
    }

    @Test
    fun `CallbackWatcher cancel detaches the listener`() {
        val watcher = CallbackWatcher()
        var fires = 0
        val reg = watcher.await(obf) { fires++ }
        reg.cancel()
        watcher.signalLoadable(obf)
        assertEquals(0, fires)
    }

    @Test
    fun `a watcher that re-fires after the load still invokes the callback once`() {
        // A misbehaving watcher whose cancel() is a no-op and that fires its
        // listener twice after the class is loadable. The first fire wins the
        // run-once race (compareAndSet true); the second re-probes (still true)
        // but loses the race (compareAndSet false) — so the callback runs once.
        val late = LateLoadingClassLoader(javaClass.classLoader, setOf(obf))
        val rosetta = rosettaOver(late)
        var captured: (() -> Unit)? = null
        val noisyWatcher =
            ClassAvailabilityWatcher { _, onLoadable ->
                captured = onLoadable
                Registration { /* deliberately does NOT detach */ }
            }
        var fires = 0

        DeferredBinding.whenClassAvailable(rosetta, "com.example.RealClient", noisyWatcher) { fires++ }
        late.release()
        captured!!() // first fire → callback
        captured!!() // second fire → loses compareAndSet, no refire
        assertEquals(1, fires)
    }

    // ---- denied/reserved obf target → TargetPolicyException, never loaded ----

    @Test
    fun `a denied obf target throws TargetPolicyException and is never probe-loaded`() {
        val maliciousMap =
            MapLoader.fromJson(
                """
                {
                  "schema_version": 3,
                  "app": "com.example.app",
                  "version": "1.0.0",
                  "version_code": 100,
                  "classes": {
                    "com.example.RealClient": {
                      "obfuscated": "java.lang.Runtime",
                      "methods": {}, "fields": {}
                    }
                  }
                }
                """.trimIndent(),
            )
        // Gate the forbidden FQN so any load attempt is observable.
        val late = LateLoadingClassLoader(javaClass.classLoader, setOf("java.lang.Runtime"))
        val rosetta = RosettaXposed.fromMapUnverified(maliciousMap, late)
        val watcher = CallbackWatcher()

        val ex =
            assertFailsWith<TargetPolicyException> {
                DeferredBinding.whenClassAvailable(rosetta, "com.example.RealClient", watcher) {
                    error("must never fire for a denied target")
                }
            }
        assertEquals("java.lang.Runtime", ex.target)
        assertEquals("com.example.RealClient", ex.name)
        // M1: the guard threw BEFORE any Class.forName — the loader saw no load.
        assertEquals(0, late.loadAttempts)
    }

    // ---- resolution still works while the class is absent --------------------

    @Test
    fun `resolution works while absent but member bind throws until released`() {
        val late = LateLoadingClassLoader(javaClass.classLoader, setOf(obf))
        val rosetta = rosettaOver(late)

        // Resolution is pure data — available even though the class isn't.
        assertTrue(rosetta.knows("com.example.RealClient"))
        val target = rosetta.method("com.example.RealClient", "single")

        // Binding the member needs the class loaded — fails until released.
        assertFailsWith<BindException> { target.member() }

        late.release()
        assertEquals("c", target.member().name)
    }

    // ---- the RosettaXposed.deferred convenience forwards ---------------------

    @Test
    fun `RosettaXposed deferred convenience forwards to DeferredBinding`() {
        val late = LateLoadingClassLoader(javaClass.classLoader, setOf(obf))
        val rosetta = rosettaOver(late)
        val ticker = ManualTicker()
        var fires = 0

        rosetta.deferred("com.example.RealClient", ClassLoaderProbeWatcher(ticker)) { fires++ }
        assertEquals(0, fires)
        late.release()
        ticker.tick()
        assertEquals(1, fires)
    }
}
