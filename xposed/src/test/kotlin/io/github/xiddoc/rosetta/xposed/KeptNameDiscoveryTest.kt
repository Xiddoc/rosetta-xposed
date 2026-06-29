/*
 * Kept-name member-harvest tests for the dynamic (self-healing) backend
 * (#47 — strategy e).
 *
 * Split from DynamicResolutionBackendTest so each file stays under detekt's
 * LargeClass budget (the same reason DynamicOverloadDiscoveryTest /
 * CompositeDiscoveryWiringTest were split off). These pin that once a class is
 * located, every member DexKit enumerates is keyed by its OWN obfuscated short
 * name, so a method R8 KEPT (an app's "kept carve-out" — e.g. TickTick's
 * stringless GreenDAO accessors `User#isPro` / `getProType`) resolves with NO
 * per-method signature, while a genuinely RENAMED method is never fabricated.
 * Everything runs on a plain JVM via [FakeDexKitIndex].
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.AmbiguousOverloadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeptNameDiscoveryTest {
    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"
    private val real = "com.example.RealClient"

    @Test
    fun `resolveMethod resolves a kept-name member with no signature hint`() {
        // The class is located by an anchor; the method `isPro` carries NO hint
        // (its only signature was structural smali, dropped at harvest). Because
        // R8 KEPT the name, membersOf exposes it under its own (== real) name and
        // it resolves — the TickTick `User#isPro` self-heal case.
        val anchors = listOf("user_anchor")
        val index =
            FakeDexKitIndex(
                byAnchors = mapOf(anchors to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "isPro", "()Z"))),
            )
        val backend = DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(anchors = anchors)))
        val m = backend.resolveMethod(real, "isPro")
        assertEquals("isPro", m.obfName)
        assertEquals(obf, m.className)
        assertEquals("()Z", m.signature)
    }

    @Test
    fun `the kept-name harvest exposes every kept member of a located class`() {
        // All of a kept carve-out's stringless accessors resolve off one class
        // location — no per-method signature for any of them.
        val anchors = listOf("user_anchor")
        val index =
            FakeDexKitIndex(
                byAnchors = mapOf(anchors to obf),
                methods =
                    mapOf(
                        obf to
                            listOf(
                                MethodMatch(obf, "isPro", "()Z"),
                                MethodMatch(obf, "getProType", "()I"),
                                MethodMatch(obf, "isActiveTeamUser", "()Z"),
                            ),
                    ),
            )
        val backend = DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(anchors = anchors)))
        assertEquals("isPro", backend.resolveMethod(real, "isPro").obfName)
        assertEquals("getProType", backend.resolveMethod(real, "getProType").obfName)
        assertEquals("isActiveTeamUser", backend.resolveMethod(real, "isActiveTeamUser").obfName)
    }

    @Test
    fun `the kept-name harvest retains overloads and disambiguates by argTypes`() {
        // Two kept overloads of `valueOf` (one duplicate is collapsed). argTypes
        // selects; no-argTypes is ambiguous — static-resolver parity.
        val anchors = listOf("anchor")
        val index =
            FakeDexKitIndex(
                byAnchors = mapOf(anchors to obf),
                methods =
                    mapOf(
                        obf to
                            listOf(
                                MethodMatch(obf, "valueOf", "(I)Lp;"),
                                MethodMatch(obf, "valueOf", "(J)Lp;"),
                                MethodMatch(obf, "valueOf", "(I)Lp;"), // exact dup → collapsed
                            ),
                    ),
            )
        val backend = DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(anchors = anchors)))
        val intArg = backend.resolveMethod(real, "valueOf", listOf("int"))
        assertEquals("(I)Lp;", intArg.signature)
        assertEquals(2, intArg.allOverloads.size) // duplicate collapsed to two
        assertFailsWith<AmbiguousOverloadException> { backend.resolveMethod(real, "valueOf") }
    }

    @Test
    fun `the kept-name harvest does not fabricate a renamed method`() {
        // membersOf reports only the obfuscated short name `a`; a request for the
        // REAL name `wasRenamed` finds nothing (the kept harvest keys by `a`, not
        // the real name) and fails closed — a renamed, stringless method genuinely
        // cannot be self-healed without a richer signature.
        val anchors = listOf("anchor")
        val index =
            FakeDexKitIndex(
                byAnchors = mapOf(anchors to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "a", "()Z"))),
            )
        val backend = DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(anchors = anchors)))
        // The kept (obf) name IS resolvable as itself...
        assertEquals("a", backend.resolveMethod(real, "a").obfName)
        // ...but the real renamed name is not fabricated.
        assertFailsWith<DiscoveryException> { backend.resolveMethod(real, "wasRenamed") }
    }

    @Test
    fun `the kept-name harvest skips a synthetic bridge so it cannot shadow the real method`() {
        // #47 correctness: a kept class exposes the real `compareTo(MyType)` AND a
        // compiler covariant-return BRIDGE `compareTo(Object)` under the SAME obf
        // name. Without the synthetic filter both register as overloads of
        // `compareTo`, and an argTypes lookup (params only, return ignored) could
        // pick the bridge. The filter drops the synthetic, so only the real one
        // remains and no-argTypes resolution is unambiguous.
        val anchors = listOf("anchor")
        val index =
            FakeDexKitIndex(
                byAnchors = mapOf(anchors to obf),
                methods =
                    mapOf(
                        obf to
                            listOf(
                                MethodMatch(obf, "compareTo", "(Lp;)I"),
                                MethodMatch(obf, "compareTo", "(Ljava/lang/Object;)I", isSynthetic = true),
                            ),
                    ),
            )
        val backend = DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(anchors = anchors)))
        val m = backend.resolveMethod(real, "compareTo")
        assertEquals("(Lp;)I", m.signature)
        assertEquals(1, m.allOverloads.size) // the synthetic bridge was filtered out
    }

    @Test
    fun `a hint for a kept name unions with, not replaces, the harvested overloads`() {
        // #48: a kept method `valueOf` has TWO kept overloads; the contributor
        // also ships a (descriptor) hint for one of them. The merge must UNION —
        // an earlier bug let the single hinted overload REPLACE the whole
        // harvested set, dropping valueOf(J). Both must stay resolvable.
        val anchors = listOf("anchor")
        val index =
            FakeDexKitIndex(
                byAnchors = mapOf(anchors to obf),
                methods =
                    mapOf(
                        obf to
                            listOf(
                                MethodMatch(obf, "valueOf", "(I)Lp;"),
                                MethodMatch(obf, "valueOf", "(J)Lp;"),
                            ),
                    ),
            )
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(
                    real to
                        DiscoveryHints(
                            anchors = anchors,
                            methods = listOf(MethodDiscoveryHint(realName = "valueOf", descriptor = "(I)Lp;")),
                        ),
                ),
            )
        // Both kept overloads survive the merge (the hinted (I) collapsed into the
        // harvested (I); the harvested (J) was NOT dropped).
        assertEquals("(I)Lp;", backend.resolveMethod(real, "valueOf", listOf("int")).signature)
        assertEquals("(J)Lp;", backend.resolveMethod(real, "valueOf", listOf("long")).signature)
        assertEquals(2, backend.resolveMethod(real, "valueOf", listOf("int")).allOverloads.size)
    }

    @Test
    fun `a signature hint overrides the kept-name identity for a renamed method`() {
        // `single` was RENAMED to `c`; its descriptor hint maps real → obf. The
        // kept harvest also keys `c` → `c` (inert). Both resolve: the real name
        // via the hint, the obf name via the kept identity.
        val anchors = listOf("anchor")
        val index =
            FakeDexKitIndex(
                byAnchors = mapOf(anchors to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "c", "(Ljava/lang/String;)V"))),
            )
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(
                    real to
                        DiscoveryHints(
                            anchors = anchors,
                            methods = listOf(MethodDiscoveryHint(realName = "single", descriptor = "(Ljava/lang/String;)V")),
                        ),
                ),
            )
        assertEquals("c", backend.resolveMethod(real, "single").obfName)
        assertEquals("c", backend.resolveMethod(real, "c").obfName)
    }
}
