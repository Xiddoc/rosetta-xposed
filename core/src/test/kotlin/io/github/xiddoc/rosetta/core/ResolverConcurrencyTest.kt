/*
 * xposed#10 — :core Resolver thread-safety.
 *
 * An Xposed module shares one Resolver across the app's arbitrary hook
 * threads. These tests stress concurrent resolveClass / resolveMethod /
 * resolveField / override from many threads and assert no exception escapes
 * and every result is correct. They would flake / crash against the previous
 * bare `mutableMapOf` caches (concurrent HashMap mutation), and pass against
 * the ConcurrentHashMap + computeIfAbsent implementation.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.FieldEntry
import io.github.xiddoc.rosetta.core.model.MethodEntry
import io.github.xiddoc.rosetta.core.model.MethodOverloads
import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.resolver.DiscoveredClass
import io.github.xiddoc.rosetta.core.resolver.Resolver
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverConcurrencyTest {
    private fun bigMap(n: Int): RosettaMap {
        val classes =
            (0 until n).associate { i ->
                "com.example.app.Cls$i" to
                    ClassEntry(
                        obfuscated = "c$i",
                        methods = mapOf("m$i" to MethodOverloads(listOf(MethodEntry(obfuscated = "x$i", signature = "()V")))),
                        fields = mapOf("f$i" to FieldEntry(obfuscated = "g$i", type = "I")),
                    )
            }
        return RosettaMap(
            schemaVersion = 2,
            app = "com.example.app",
            version = "1.0.0",
            versionCode = 100,
            classes = classes,
        )
    }

    @Test
    fun `concurrent resolveClass resolveMethod resolveField are safe and correct`() {
        val n = 200
        val resolver = Resolver(bigMap(n))
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val mismatches = ConcurrentLinkedQueue<String>()

        repeat(threads) { t ->
            pool.submit {
                try {
                    start.await()
                    // Each worker hammers the SAME key space, so many threads
                    // race to populate the same cache slots (computeIfAbsent).
                    repeat(50) { pass ->
                        for (i in 0 until n) {
                            val idx = (i + t + pass) % n
                            val real = "com.example.app.Cls$idx"
                            if (resolver.resolveClass(real).obfName != "c$idx") mismatches.add("class $idx")
                            if (resolver.resolveMethod(real, "m$idx").obfName != "x$idx") mismatches.add("method $idx")
                            if (resolver.resolveField(real, "f$idx").obfName != "g$idx") mismatches.add("field $idx")
                        }
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish — possible livelock")

        assertTrue(errors.isEmpty(), "concurrent resolution threw: ${errors.firstOrNull()}")
        assertTrue(mismatches.isEmpty(), "wrong result under concurrency: ${mismatches.firstOrNull()}")
    }

    @Test
    fun `concurrent override and resolve do not corrupt the caches`() {
        val n = 100
        val resolver = Resolver(bigMap(n))
        val threads = 12
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()

        repeat(threads) { t ->
            pool.submit {
                try {
                    start.await()
                    repeat(40) {
                        for (i in 0 until n) {
                            // Half the workers re-point via override; all read.
                            if (t % 2 == 0) {
                                resolver.override(DiscoveredClass(realName = "com.example.app.Cls$i", obfName = "d$i"))
                            }
                            resolver.resolveClass("com.example.app.Cls$i")
                            resolver.reverseLookup("c$i")
                            resolver.reverseLookup("d$i")
                        }
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish — possible livelock")
        assertTrue(errors.isEmpty(), "concurrent override/resolve threw: ${errors.firstOrNull()}")

        // After all overrides, every class resolves to its overridden obf and
        // the reverse index points back at the real name (re-point applied).
        for (i in 0 until n) {
            assertEquals("d$i", resolver.resolveClass("com.example.app.Cls$i").obfName)
            assertEquals("com.example.app.Cls$i", resolver.reverseLookup("d$i"))
        }
    }

    /**
     * xposed#13 — the override lost-invalidation race. A reader that snapshotted
     * the OLD entry must NOT re-insert its stale resolution after a concurrent
     * override→invalidate cleared the cache. This interleaves a SINGLE override
     * (the LAST writer for that round) against many concurrent
     * resolveClass/resolveMethod/resolveField readers, over many rounds with a
     * fresh obf each round, and asserts the post-quiescence cache always
     * reflects the LAST override — never a superseded value left behind by a
     * lost invalidation. Against the pre-fix code (lock-free `put` of a stale
     * snapshot after the clear) a surviving stale entry would fail one of the
     * asserts below; the generation gate suppresses that put.
     */
    @Test
    fun `override is not lost to a concurrent resolve that snapshotted the old entry`() {
        val resolver = Resolver(bigMap(1))
        val real = "com.example.app.Cls0"
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads + 1)
        val errors = ConcurrentLinkedQueue<Throwable>()

        repeat(300) { round ->
            val obf = "r$round"
            val start = CountDownLatch(1)
            val readers =
                (0 until threads).map {
                    pool.submit {
                        try {
                            start.await()
                            // Hammer all three read paths so a stale put on ANY
                            // of the three caches would be caught below.
                            repeat(20) {
                                resolver.resolveClass(real)
                                resolver.resolveMethod(real, "m0")
                                resolver.resolveField(real, "f0")
                            }
                        } catch (e: Throwable) {
                            errors.add(e)
                        }
                    }
                }
            val writer =
                pool.submit {
                    try {
                        start.await()
                        // The single, LAST override for this round. Carries the
                        // method/field so the method/field read paths resolve and
                        // their caches are exercised by the gate too.
                        resolver.override(
                            DiscoveredClass(
                                realName = real,
                                obfName = obf,
                                methods = mapOf("m0" to MethodOverloads(listOf(MethodEntry(obfuscated = "x0", signature = "()V")))),
                                fields = mapOf("f0" to FieldEntry(obfuscated = "g0", type = "I")),
                            ),
                        )
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            start.countDown()
            (readers + writer).forEach { it.get(30, TimeUnit.SECONDS) }

            assertTrue(errors.isEmpty(), "round $round threw: ${errors.firstOrNull()}")
            // Post-quiescence: the cache MUST reflect the round's override, not a
            // stale value a reader re-inserted after the invalidate.
            assertEquals(obf, resolver.resolveClass(real).obfName, "stale class survived round $round")
            assertEquals(obf, resolver.resolveMethod(real, "m0").className, "stale method survived round $round")
            assertEquals(obf, resolver.resolveField(real, "f0").className, "stale field survived round $round")
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
    }
}
