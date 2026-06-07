/*
 * Overload-retention tests for the dynamic (self-healing) backend (xposed#14
 * M18).
 *
 * Split from DynamicResolutionBackendTest so each file stays under detekt's
 * LargeClass budget. These pin that several [MethodDiscoveryHint]s sharing one
 * real method name are ALL retained (keyed by signature) instead of all but one
 * being lost, and that overload selection then mirrors the static Resolver:
 * argTypes disambiguate; their absence with several overloads is ambiguous; a
 * duplicate hint collapses. Everything runs on a plain JVM via [FakeDexKitIndex].
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.AmbiguousOverloadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DynamicOverloadDiscoveryTest {
    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"
    private val real = "com.example.RealClient"

    // Three overloads of real name "d": two share the obf name "d" (different
    // signatures) and one has a distinct obf name "e". Appending them exercises
    // both arms of the dedup check (obf-name-differs AND obf-same/sig-differs)
    // while retaining all three (none is an exact (obf, sig) duplicate).
    private fun threeOverloadBackend(): DynamicResolutionBackend {
        val index =
            FakeDexKitIndex(
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
                methods =
                    mapOf(
                        obf to
                            listOf(
                                MethodMatch(obf, "d", "(Ljava/lang/String;)V"),
                                MethodMatch(obf, "e", "(Ljava/lang/String;J)V"),
                                MethodMatch(obf, "d", "(I)V"),
                            ),
                    ),
            )
        return DynamicResolutionBackend(
            index,
            mapOf(
                real to
                    DiscoveryHints(
                        aidlDescriptor = "Lcom/example/IFoo;",
                        methods =
                            listOf(
                                MethodDiscoveryHint(realName = "d", descriptor = "(Ljava/lang/String;)V"),
                                MethodDiscoveryHint(realName = "d", descriptor = "(Ljava/lang/String;J)V"),
                                MethodDiscoveryHint(realName = "d", descriptor = "(I)V"),
                            ),
                    ),
            ),
        )
    }

    @Test
    fun `discovery retains all overloads of one method name and disambiguates by argTypes`() {
        // Three hints share realName "d" with distinct descriptors. Before M18
        // each overwrote the last; now ALL are retained, keyed by signature, and
        // argTypes selects the right one.
        val backend = threeOverloadBackend()

        // All overloads survive — visible via allOverloads.
        val oneArg = backend.resolveMethod(real, "d", listOf("java.lang.String"))
        assertEquals("(Ljava/lang/String;)V", oneArg.signature)
        assertEquals(3, oneArg.allOverloads.size)

        val twoArg = backend.resolveMethod(real, "d", listOf("java.lang.String", "long"))
        assertEquals("(Ljava/lang/String;J)V", twoArg.signature)
        // The selected overload is first in allOverloads (ordering parity).
        assertEquals("(Ljava/lang/String;J)V", twoArg.allOverloads.first().signature)

        val intArg = backend.resolveMethod(real, "d", listOf("int"))
        assertEquals("(I)V", intArg.signature)
    }

    @Test
    fun `resolveMethod with no argTypes is ambiguous when a name has several overloads`() {
        // With several retained overloads and no argTypes to pick, the backend
        // throws AmbiguousOverloadException (static-resolver parity) rather than
        // silently returning the first.
        val ex = assertFailsWith<AmbiguousOverloadException> { threeOverloadBackend().resolveMethod(real, "d") }
        assertEquals(3, ex.overloadCount)
        assertEquals("d", ex.methodName)
        // `classScope` (the Frida-parity field name, renamed from `className`)
        // carries the owning class scope.
        assertEquals(real, ex.classScope)
    }

    @Test
    fun `a duplicate overload hint is collapsed, not double-registered`() {
        // The same (obf name, signature) listed twice registers once — a recipe
        // that repeats an overload does not produce a phantom second entry.
        val index =
            FakeDexKitIndex(
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "c", "(Ljava/lang/String;)Ljava/lang/String;"))),
            )
        val backend =
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
                                    MethodDiscoveryHint(
                                        realName = "single",
                                        descriptor = "(Ljava/lang/String;)Ljava/lang/String;",
                                    ),
                                ),
                        ),
                ),
            )
        // Collapsed to one overload, so no-argTypes resolution is unambiguous.
        val m = backend.resolveMethod(real, "single")
        assertEquals(1, m.allOverloads.size)
        assertEquals("c", m.obfName)
    }
}
