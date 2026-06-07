/*
 * Composite backend + self-healing wiring tests (B.1).
 *
 * Split from DynamicResolutionBackendTest so each file stays focused (and
 * under detekt's LargeClass budget). This file covers the static-first
 * composite ordering, the discovery write-back (the index is consulted at most
 * once per real class), the C1 target guard applied to a discovered FQN, and
 * the `RosettaXposed.fromMapWithDiscovery` construction path (including the
 * signer-guard-before-discovery ordering). It uses the same [FakeDexKitIndex]
 * so everything runs on a plain JVM with no device.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.TargetPolicyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompositeDiscoveryWiringTest {
    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"
    private val real = "com.example.RealClient"

    // The fixture obf lives outside the `com.example` app namespace, so the
    // :core C1 guard (xposed#11) would deny it under the default policy. These
    // discovery-wiring tests are not exercising the guard, so they allowlist the
    // fixture FQN — the same escape hatch RosettaXposedTest uses — and pass it
    // into the static backend whose :core resolver now runs the guard.
    private val policy = TargetPolicy(allow = listOf(obf))

    private fun emptyStaticMap() =
        MapLoader.fromJson(
            """
            {
              "schema_version": 2,
              "app": "com.example.app",
              "version": "1.0.0",
              "version_code": 100,
              "classes": {}
            }
            """.trimIndent(),
        )

    // ---- Composite: static-first ordering + write-back ----------------------

    @Test
    fun `composite serves a static hit without touching the index`() {
        val staticMap =
            MapLoader.fromJson(
                """
                {
                  "schema_version": 2,
                  "app": "com.example.app",
                  "version": "1.0.0",
                  "version_code": 100,
                  "classes": { "$real": { "obfuscated": "$obf" } }
                }
                """.trimIndent(),
            )
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val composite =
            CompositeResolutionBackend(
                StaticResolutionBackend(staticMap, policy),
                DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;"))),
            )
        assertEquals(obf, composite.resolveClass(real).obfName)
        assertEquals(0, index.calls)
    }

    @Test
    fun `composite serves method and field from a static map without discovery`() {
        val staticMap =
            MapLoader.fromJson(
                """
                {
                  "schema_version": 2,
                  "app": "com.example.app",
                  "version": "1.0.0",
                  "version_code": 100,
                  "classes": {
                    "$real": {
                      "obfuscated": "$obf",
                      "methods": { "single": { "obfuscated": "c", "signature": "(Ljava/lang/String;)Ljava/lang/String;" } },
                      "fields": { "id": { "obfuscated": "a", "type": "Ljava/lang/String;" } }
                    }
                  }
                }
                """.trimIndent(),
            )
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val composite =
            CompositeResolutionBackend(
                StaticResolutionBackend(staticMap, policy),
                DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;"))),
            )
        assertTrue(composite.canResolve(real))
        assertEquals("c", composite.resolveMethod(real, "single").obfName)
        assertEquals("a", composite.resolveField(real, "id").obfName)
        assertEquals(0, index.calls)
    }

    @Test
    fun `composite writes a discovery back so the next lookup is static`() {
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val static = StaticResolutionBackend(emptyStaticMap(), policy)
        val composite =
            CompositeResolutionBackend(
                static,
                DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;"))),
            )
        assertTrue(composite.canResolve(real))
        assertEquals(obf, composite.resolveClass(real).obfName)
        val afterFirst = index.calls
        assertTrue(afterFirst > 0)

        // Now a static hit — the index is NOT consulted again.
        assertTrue(static.canResolve(real))
        assertEquals(obf, composite.resolveClass(real).obfName)
        assertEquals(afterFirst, index.calls)
    }

    @Test
    fun `composite resolveMethod discovers, writes back, then serves statically`() {
        val index =
            FakeDexKitIndex(
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "c", "(Ljava/lang/String;)Ljava/lang/String;"))),
            )
        val static = StaticResolutionBackend(emptyStaticMap(), policy)
        val composite =
            CompositeResolutionBackend(
                static,
                DynamicResolutionBackend(
                    index,
                    mapOf(
                        real to
                            DiscoveryHints(
                                aidlDescriptor = "Lcom/example/IFoo;",
                                methods =
                                    listOf(
                                        MethodDiscoveryHint(
                                            realName = "single",
                                            descriptor = "(Ljava/lang/String;)Ljava/lang/String;",
                                        ),
                                    ),
                            ),
                    ),
                ),
            )
        assertEquals("c", composite.resolveMethod(real, "single").obfName)
        val afterFirst = index.calls
        // Second method lookup is served from the written-back static entry.
        assertEquals("c", composite.resolveMethod(real, "single").obfName)
        assertEquals(afterFirst, index.calls)
    }

    @Test
    fun `composite resolveField discovers the class then serves the field statically`() {
        val index =
            FakeDexKitIndex(
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "c", "(Ljava/lang/String;)Ljava/lang/String;"))),
            )
        // The discovered class carries no fields, so a static field lookup misses
        // with a core ResolveException — but the class WAS written back (index
        // consulted), proving resolveField routes through the class discovery.
        val static = StaticResolutionBackend(emptyStaticMap(), policy)
        val composite =
            CompositeResolutionBackend(
                static,
                DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;"))),
            )
        assertFailsWith<io.github.xiddoc.rosetta.core.ResolveException> {
            composite.resolveField(real, "id")
        }
        assertTrue(static.canResolve(real))
    }

    @Test
    fun `composite write-back carries extends through the typed DiscoveredClass`() {
        // The typed DiscoveredClass contract must preserve `extends` from the
        // dynamic discovery into the static resolver, so a resolveClass after
        // write-back surfaces the discovered parent.
        val index = FakeDexKitIndex(bySuper = mapOf("zzzz" to obf))
        val static = StaticResolutionBackend(emptyStaticMap(), policy)
        val composite =
            CompositeResolutionBackend(
                static,
                DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(superclass = "zzzz"))),
            )
        // First lookup discovers + writes back; second is the static hit.
        assertEquals("zzzz", composite.resolveClass(real).extends)
        assertEquals("zzzz", static.resolveClass(real).extends)
    }

    @Test
    fun `composite canResolve is false when neither backend knows the name`() {
        val composite =
            CompositeResolutionBackend(
                StaticResolutionBackend(emptyStaticMap(), policy),
                DynamicResolutionBackend(FakeDexKitIndex(), emptyMap()),
            )
        assertTrue(!composite.canResolve("com.example.Unknown"))
    }

    // ---- C1: a discovered FQN in a reserved namespace is guarded ------------

    @Test
    fun `a discovered reserved-namespace FQN is rejected by the C1 target guard`() {
        // The fake "discovers" java.lang.Runtime as the obf class. Discovery
        // itself succeeds (the backend only PRODUCES the name); the C1 guard
        // rejects it when the binding tries to realise the target.
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to "java.lang.Runtime"))
        val rosetta =
            RosettaXposed.fromMapWithDiscovery(
                map = emptyStaticMap(),
                index = index,
                classLoader = javaClass.classLoader,
                discovery = DiscoveryConfig(hints = mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;"))),
            )
        val ex = assertFailsWith<TargetPolicyException> { rosetta.useClass(real).load() }
        assertEquals("java.lang.Runtime", ex.target)
        assertEquals(real, ex.name)
    }

    // ---- fromMapWithDiscovery end-to-end ------------------------------------

    @Test
    fun `fromMapWithDiscovery with all defaults omitted uses NOOP sink and default policy`() {
        // Omits identity, discovery, and policy so their default-value
        // expressions (no signer check; empty DiscoveryConfig; default
        // TargetPolicy) all execute.
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val rosetta =
            RosettaXposed.fromMapWithDiscovery(
                emptyStaticMap(),
                index,
                javaClass.classLoader,
            )
        // No hints were supplied, so the dynamic backend cannot locate it; the
        // static map is empty too, so it is simply unknown.
        assertTrue(!rosetta.knows(real))
        assertEquals(0, index.calls)
    }

    @Test
    fun `fromMapWithDiscovery binds a discovered class through the loader`() {
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val sink = MapDiscoverySink()
        val rosetta =
            RosettaXposed.fromMapWithDiscovery(
                map = emptyStaticMap(),
                index = index,
                classLoader = javaClass.classLoader,
                discovery =
                    DiscoveryConfig(
                        hints = mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;")),
                        sink = sink,
                    ),
                policy = TargetPolicy(allow = listOf(obf)),
            )
        assertTrue(rosetta.knows(real))
        assertEquals(obf, rosetta.useClass(real).load().name)
        assertEquals(1, sink.entries().size)
    }

    @Test
    fun `fromMapWithDiscovery wires the static map translator into dynamic argTypes`() {
        // F1 end-to-end: the static map maps an arg class real → obf, but the
        // class being hooked is discovered dynamically. The discovered overload
        // carries the OBFUSCATED arg ref; the caller hooks with the REAL arg
        // name. fromMapWithDiscovery must feed the static map's translator into
        // the dynamic backend so the real → obf translation matches the
        // discovered descriptor (identity translate would have thrown).
        val mapWithArg =
            MapLoader.fromJson(
                """
                {
                  "schema_version": 2,
                  "app": "com.example.app",
                  "version": "1.0.0",
                  "version_code": 100,
                  "classes": { "com.example.RealArg": { "obfuscated": "obfArg" } }
                }
                """.trimIndent(),
            )
        val index =
            FakeDexKitIndex(
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "c", "(LobfArg;)V"))),
            )
        val rosetta =
            RosettaXposed.fromMapWithDiscovery(
                map = mapWithArg,
                index = index,
                classLoader = javaClass.classLoader,
                discovery =
                    DiscoveryConfig(
                        hints =
                            mapOf(
                                real to
                                    DiscoveryHints(
                                        aidlDescriptor = "Lcom/example/IFoo;",
                                        methods = listOf(MethodDiscoveryHint(realName = "single", descriptor = "(LobfArg;)V")),
                                    ),
                            ),
                    ),
                policy = TargetPolicy(allow = listOf(obf)),
            )
        // Hook the discovered method by its REAL arg type; the static map's
        // translator turns "com.example.RealArg" → "obfArg" so it matches.
        val m = rosetta.method(real, "single", listOf("com.example.RealArg"))
        assertEquals("c", m.resolved.obfName)
    }

    @Test
    fun `fromMapWithDiscovery enforces the signer guard before wiring discovery`() {
        val signedMap =
            MapLoader.fromJson(
                """
                {
                  "schema_version": 2,
                  "app": "com.example.app",
                  "version": "1.0.0",
                  "version_code": 100,
                  "signer_sha256": "${"a".repeat(64)}",
                  "classes": {}
                }
                """.trimIndent(),
            )
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        // Identity presents a DIFFERENT signer hash → fail closed before discovery.
        val identity =
            AppIdentity(packageName = "com.example.app", versionCode = 100, signerSha256s = setOf("b".repeat(64)))
        assertFailsWith<io.github.xiddoc.rosetta.core.SignerMismatchException> {
            RosettaXposed.fromMapWithDiscovery(
                map = signedMap,
                index = index,
                classLoader = javaClass.classLoader,
                identity = identity,
                discovery = DiscoveryConfig(hints = mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;"))),
            )
        }
        // Discovery never ran.
        assertEquals(0, index.calls)
    }

    @Test
    fun `fromMapWithDiscovery with a matching identity proceeds to discovery`() {
        val signedMap =
            MapLoader.fromJson(
                """
                {
                  "schema_version": 2,
                  "app": "com.example.app",
                  "version": "1.0.0",
                  "version_code": 100,
                  "signer_sha256": "${"a".repeat(64)}",
                  "classes": {}
                }
                """.trimIndent(),
            )
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val identity =
            AppIdentity(packageName = "com.example.app", versionCode = 100, signerSha256s = setOf("a".repeat(64)))
        val rosetta =
            RosettaXposed.fromMapWithDiscovery(
                map = signedMap,
                index = index,
                classLoader = javaClass.classLoader,
                identity = identity,
                discovery = DiscoveryConfig(hints = mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;"))),
                policy = TargetPolicy(allow = listOf(obf)),
            )
        assertEquals(obf, rosetta.useClass(real).load().name)
    }

    // ---- M5: explicit, safe handling of the signer-skip path ----------------

    private fun signedMap() =
        MapLoader.fromJson(
            """
            {
              "schema_version": 2,
              "app": "com.example.app",
              "version": "1.0.0",
              "version_code": 100,
              "signer_sha256": "${"a".repeat(64)}",
              "classes": {}
            }
            """.trimIndent(),
        )

    @Test
    fun `fromMapWithDiscovery refuses a signed map with no identity unless opted in`() {
        // xposed#14 M5: a map demanding a signer, built with NO identity and
        // without allowUnverified, fails closed rather than silently skipping the
        // guard. Discovery never runs.
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        assertFailsWith<UnverifiedDiscoveryException> {
            RosettaXposed.fromMapWithDiscovery(
                map = signedMap(),
                index = index,
                classLoader = javaClass.classLoader,
            )
        }
        assertEquals(0, index.calls)
    }

    @Test
    fun `fromMapWithDiscovery accepts a signed map with no identity when allowUnverified is true`() {
        // The explicit opt-in deliberately skips the signer guard (e.g. early
        // bring-up before an AppIdentity is wired). Construction succeeds.
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val rosetta =
            RosettaXposed.fromMapWithDiscovery(
                map = signedMap(),
                index = index,
                classLoader = javaClass.classLoader,
                allowUnverified = true,
                policy = policy,
            )
        // Unsigned/empty classes, no hints → simply unknown, but it CONSTRUCTED.
        assertTrue(!rosetta.knows(real))
    }

    @Test
    fun `fromMapWithDiscovery needs no opt-in for an unsigned map with no identity`() {
        // An unsigned map has no guard to skip, so the default (allowUnverified
        // = false, no identity) path constructs without throwing.
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val rosetta =
            RosettaXposed.fromMapWithDiscovery(
                map = emptyStaticMap(),
                index = index,
                classLoader = javaClass.classLoader,
            )
        assertTrue(!rosetta.knows(real))
    }

    @Test
    fun `UnverifiedDiscoveryException is not an XposedBindingFailure`() {
        // M5: the construction-time security refusal must NOT be swallowed by a
        // module's per-target binding-failure catch clause.
        assertTrue(UnverifiedDiscoveryException("x") !is XposedBindingFailure)
    }
}
