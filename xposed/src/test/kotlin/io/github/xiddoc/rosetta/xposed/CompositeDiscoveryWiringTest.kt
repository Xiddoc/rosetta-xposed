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
                StaticResolutionBackend(staticMap),
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
                StaticResolutionBackend(staticMap),
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
        val static = StaticResolutionBackend(emptyStaticMap())
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
        val static = StaticResolutionBackend(emptyStaticMap())
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
        val static = StaticResolutionBackend(emptyStaticMap())
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
    fun `composite canResolve is false when neither backend knows the name`() {
        val composite =
            CompositeResolutionBackend(
                StaticResolutionBackend(emptyStaticMap()),
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
}
