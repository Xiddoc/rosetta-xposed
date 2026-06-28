/*
 * Regex-anchor discovery (strategy b′) tests.
 *
 * The dynamic backend gained a class-locating strategy that matches string
 * CONSTANTS by RE2 regex (the genuine-regex form of the exact `anchors`
 * strategy) — the on-device home of a sigmatcher `type: regex` signature whose
 * pattern is a real regex. These pin the new strategy, its place in the
 * strategy order, its fail-closed SafePattern routing, and the
 * [FakeDexKitIndex] / [DiscoveryHints] surface it added. Kept separate from
 * DynamicResolutionBackendTest to stay under detekt's LargeClass budget.
 */
package io.github.xiddoc.rosetta.xposed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegexAnchorDiscoveryTest {
    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"
    private val real = "com.example.RealClient"

    @Test
    fun `discovers a class by a regex string anchor`() {
        val patterns = listOf("https://.*\\.example/api")
        val index = FakeDexKitIndex(byPatterns = mapOf(patterns to obf))
        val backend = DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(regexAnchors = patterns)))
        assertTrue(backend.canResolve(real))
        assertEquals(obf, backend.resolveClass(real).obfName)
    }

    @Test
    fun `canLocateClass is true for a regex-anchor-only hint`() {
        val h = DiscoveryHints(regexAnchors = listOf("a.*"))
        assertTrue(h.canLocateClass)
        assertEquals(listOf("a.*"), h.regexAnchors)
    }

    @Test
    fun `strategy order falls through aidl and exact anchors to regex anchors`() {
        // aidl + exact anchors both miss; the regex anchor hits, BEFORE the
        // superclass strategy would — confirming the (a)->(b)->(b′)->(c) order.
        val patterns = listOf("tok.*")
        val index =
            FakeDexKitIndex(
                byPatterns = mapOf(patterns to obf),
                bySuper = mapOf("zzzz" to "io.github.xiddoc.rosetta.xposed.fixtures.Wrong"),
            )
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(
                    real to
                        DiscoveryHints(
                            aidlDescriptor = "Lcom/example/IMissing;",
                            anchors = listOf("nope"),
                            regexAnchors = patterns,
                            superclass = "zzzz",
                        ),
                ),
            )
        assertEquals(obf, backend.resolveClass(real).obfName)
    }

    @Test
    fun `regex anchors that miss fall through to the superclass strategy`() {
        // regexAnchors present but no SimilarRegex hit → the backend keeps going
        // to (c) superclass, exercising the regex-anchor miss / fall-through arm.
        val index = FakeDexKitIndex(bySuper = mapOf("zzzz" to obf))
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(real to DiscoveryHints(regexAnchors = listOf("no.*match"), superclass = "zzzz")),
            )
        assertEquals(obf, backend.resolveClass(real).obfName)
    }

    @Test
    fun `an over-count regex-anchor list fails closed via the backend`() {
        val tooMany = (0..SafePattern.MAX_ANCHORS).map { "a$it" }
        val backend = DynamicResolutionBackend(FakeDexKitIndex(), mapOf(real to DiscoveryHints(regexAnchors = tooMany)))
        assertFailsWith<DiscoveryException> { backend.resolveClass(real) }
    }

    @Test
    fun `a malformed regex anchor fails closed via the backend`() {
        val backend =
            DynamicResolutionBackend(FakeDexKitIndex(), mapOf(real to DiscoveryHints(regexAnchors = listOf("(unclosed"))))
        assertFailsWith<DiscoveryException> { backend.resolveClass(real) }
    }

    @Test
    fun `FakeDexKitIndex findClassByStringPatterns returns the seeded hit or null`() {
        val index = FakeDexKitIndex(byPatterns = mapOf(listOf("x") to obf))
        assertEquals(obf, index.findClassByStringPatterns(listOf("x")))
        assertNull(index.findClassByStringPatterns(listOf("y")))
    }
}
