/*
 * xposed#13 — opt-in fuzzy versionName fallback in :core (Kotlin twin of the
 * Frida `versionMatch: 'fuzzy'` path) and the lexicographic semver ranking that
 * replaces the overflow-prone weighted-sum heuristic.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.version.MapRegistry
import io.github.xiddoc.rosetta.core.version.MatchedBy
import io.github.xiddoc.rosetta.core.version.VersionMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FuzzyVersionMatchTest {
    private var nextCode = 1L

    private fun mapAt(label: String): RosettaMap =
        RosettaMap(
            schemaVersion = 3,
            app = "com.example.app",
            version = label,
            versionCode = nextCode++,
            classes = mapOf("com.example.Foo" to ClassEntry(obfuscated = "a")),
        )

    private fun registryOf(vararg labels: String): MapRegistry = MapRegistry.fromCollection(labels.map { mapAt(it) })

    @Test
    fun `fuzzy is off by default — an exact miss returns null`() {
        val r = registryOf("1.0.0", "1.2.0")
        assertNull(VersionMatch.select(r, versionLabel = "1.1.0"))
    }

    @Test
    fun `fuzzy picks the closest label when opted in`() {
        val r = registryOf("1.0.0", "1.2.0", "2.0.0")
        val sel = VersionMatch.select(r, versionLabel = "1.1.0", allowFuzzyMatch = true)
        assertEquals(MatchedBy.FUZZY_LABEL, sel!!.matchedBy)
        // 1.1.0 is equidistant from 1.0.0 and 1.2.0 in patch terms, but the
        // minor distance is 1 for both; the tie breaks on the LOWER version.
        assertEquals("1.0.0", sel.map.version)
    }

    @Test
    fun `lexicographic ranking — 1_0_142 vs 1_1_42 do not collide`() {
        // The old weighted sum (major*10000 + minor*100 + patch) gave both
        // candidates distance 142 from 1.0.0, a spurious tie. Component-wise:
        // |1.0.142 - 1.0.0| = [0,0,142]; |1.1.42 - 1.0.0| = [0,1,42].
        // Lexicographically [0,0,142] < [0,1,42], so 1.0.142 is strictly closer.
        val r = registryOf("1.0.142", "1.1.42")
        val sel = VersionMatch.select(r, versionLabel = "1.0.0", allowFuzzyMatch = true)
        assertEquals("1.0.142", sel!!.map.version)

        // And the symmetric case: target nearer 1.1.42 picks it.
        val sel2 = VersionMatch.select(r, versionLabel = "1.1.50", allowFuzzyMatch = true)
        assertEquals("1.1.42", sel2!!.map.version)
    }

    @Test
    fun `major distance dominates minor and patch`() {
        // 2.0.0 is one major away; 1.9.99 is far in minor/patch but same major.
        val r = registryOf("1.9.99", "2.0.0")
        val sel = VersionMatch.select(r, versionLabel = "1.0.0", allowFuzzyMatch = true)
        // Major diff: 1.9.99 -> [0,9,99]; 2.0.0 -> [1,0,0]. [0,9,99] < [1,0,0].
        assertEquals("1.9.99", sel!!.map.version)
    }

    @Test
    fun `pre-release and build suffixes are stripped before ranking`() {
        val r = registryOf("1.2.0-rc1", "3.0.0")
        val sel = VersionMatch.select(r, versionLabel = "1.2.0+build7", allowFuzzyMatch = true)
        // 1.2.0-rc1 parses to [1,2,0], an exact tuple match → distance 0.
        assertEquals("1.2.0-rc1", sel!!.map.version)
    }

    @Test
    fun `non-numeric and missing components clamp to zero`() {
        val r = registryOf("2", "abc")
        // target "1" -> [1,0,0]; "2" -> [2,0,0] dist [1,0,0]; "abc" -> [0,0,0] dist [1,0,0].
        // Tie on distance; lower version wins → "abc" ([0,0,0]) beats "2" ([2,0,0]).
        val sel = VersionMatch.select(r, versionLabel = "1", allowFuzzyMatch = true)
        assertEquals("abc", sel!!.map.version)
    }

    @Test
    fun `exact label match wins before fuzzy even when fuzzy is on`() {
        val r = registryOf("1.0.0", "1.0.1")
        val sel = VersionMatch.select(r, versionLabel = "1.0.1", allowFuzzyMatch = true)
        assertEquals(MatchedBy.LABEL, sel!!.matchedBy)
        assertEquals("1.0.1", sel.map.version)
    }

    @Test
    fun `version_code still wins before any label or fuzzy path`() {
        val r = registryOf("1.0.0", "1.0.1")
        val code = r.byLabel("1.0.1")!!.versionCode
        val sel = VersionMatch.select(r, versionCode = code, versionLabel = "9.9.9", allowFuzzyMatch = true)
        assertEquals(MatchedBy.VERSION_CODE, sel!!.matchedBy)
        assertEquals("1.0.1", sel.map.version)
    }

    @Test
    fun `fuzzy with no label returns null — nothing to compare`() {
        val r = registryOf("1.0.0")
        assertNull(VersionMatch.select(r, allowFuzzyMatch = true))
    }

    @Test
    fun `labels exposes the registered version labels`() {
        val r = registryOf("1.0.0", "2.0.0")
        assertEquals(setOf("1.0.0", "2.0.0"), r.labels)
    }

    @Test
    fun `fuzzy on an empty registry with a label returns null`() {
        // No labels to rank — fuzzySelect's empty-set guard returns null rather
        // than picking from nothing.
        val empty = MapRegistry.fromCollection(emptyList())
        assertNull(VersionMatch.select(empty, versionLabel = "1.0.0", allowFuzzyMatch = true))
    }

    @Test
    fun `ties between labels parsing to the same tuple break on the raw string`() {
        // Both rc labels parse to [1,2,0] and are equidistant from the target,
        // so the distance and parsed-version comparisons tie; the final raw
        // string compare decides — "rc1" sorts before "rc2".
        val r = registryOf("1.2.0-rc2", "1.2.0-rc1")
        val sel = VersionMatch.select(r, versionLabel = "1.2.0", allowFuzzyMatch = true)
        assertEquals("1.2.0-rc1", sel!!.map.version)
    }

    @Test
    fun `fuzzy ranking handles an empty version component clamped to zero`() {
        // A label with an empty middle component ("1..2") exercises the
        // isNullOrEmpty branch of the numeric parse (empty, not just missing).
        val r = registryOf("1..2", "5.0.0")
        val sel = VersionMatch.select(r, versionLabel = "1.0.0", allowFuzzyMatch = true)
        // "1..2" -> [1,0,2] (empty minor clamps to 0); distance from [1,0,0] is
        // [0,0,2], far closer than "5.0.0" ([4,0,0]).
        assertEquals("1..2", sel!!.map.version)
    }
}
