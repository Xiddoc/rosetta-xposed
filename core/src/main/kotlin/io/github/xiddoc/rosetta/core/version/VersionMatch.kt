/*
 * Selecting a RosettaMap for the running app — Kotlin twin of the
 * version_code-primary selection in rosetta-frida's
 * `src/session/version-match.ts`.
 *
 * Selection precedence (RFC 0001 Decision 3):
 *   1. version_code — the authoritative, O(1) key. Exact, never fuzzy.
 *   2. version label (exact) — the fallback when no version_code is
 *      available or no map carries the detected code.
 *   3. version label (fuzzy) — OPT-IN ONLY (`allowFuzzyMatch = true`,
 *      RFC 0001 Decision 3): the closest available label by
 *      component-wise semver distance. Off by default because a
 *      wrong-version map silently corrupts hooks, so an exact miss should
 *      fail loudly unless the caller explicitly allows the fallback. This
 *      is the Kotlin twin of the Frida `versionMatch: 'fuzzy'` path
 *      (`src/session/version-match.ts`).
 *
 * Fuzzy comparison parses each label into a `(major, minor, patch)` tuple
 * (missing components default to 0; pre-release / build suffixes are
 * stripped) and ranks candidates by LEXICOGRAPHIC component distance —
 * NOT a weighted positional sum. The old `major*10000 + minor*100 + patch`
 * heuristic OVERFLOWED its positional buckets once a component reached its
 * weight (e.g. `1.0.142` tied `1.1.42`); component-wise comparison cannot
 * collide that way (xposed#13). Ties break on the lower version, then the
 * raw label string, so the result is total and deterministic.
 *
 * The on-device knowledge-base layout keys files by `(app, version_code)`
 * for exactly this reason: the primary selection key is the file name.
 */
package io.github.xiddoc.rosetta.core.version

import io.github.xiddoc.rosetta.core.model.RosettaMap

/**
 * A set of single-version maps, indexed for selection (RFC 0001 Decision 3).
 *
 * The primary key is the authoritative `version_code`, so selection by code is
 * genuinely O(1) — not an O(n) scan over the values (the previous label-keyed
 * `Map<String, RosettaMap>` only LOOKED O(1)). A secondary label index backs
 * the human-label fallback.
 *
 * Build it once from the maps you bundled ([of] / [fromCollection]); both
 * indices are FIRST-WINS on a duplicate key — the first map to claim a
 * `version_code` (or label) keeps it. This is the CROSS-CLIENT CANONICAL
 * collision policy: the frida `versionCodeIndex` in
 * `src/session/version-match.ts` uses the same putIfAbsent rule, so a
 * duplicate-laden bundle selects the same map on both clients. Do not flip
 * this to last-wins without changing the frida twin in lockstep. A
 * `version_code` collision (two bundled maps sharing one code — an authoring
 * mistake for the community-maps use case) is therefore silent in the
 * *result*, but observable: [inputCount] (the number of maps fed in) exceeding
 * [size] (the number of distinct `version_code`s indexed) tells a caller that a
 * collapse happened.
 */
public class MapRegistry private constructor(
    private val byVersionCode: Map<Long, RosettaMap>,
    private val byLabel: Map<String, RosettaMap>,
    /** The number of maps fed into [fromCollection] (before de-duplication). */
    public val inputCount: Int,
) {
    /** The map registered for [versionCode] (O(1)), or null. */
    public fun byVersionCode(versionCode: Long): RosettaMap? = byVersionCode[versionCode]

    /** The map registered for the version [label] (O(1)), or null. */
    public fun byLabel(label: String): RosettaMap? = byLabel[label]

    /**
     * The registered version labels (the fuzzy-fallback candidate keys). Read
     * by [VersionMatch] when `allowFuzzyMatch` is on to scan for the closest
     * label; exposed (read-only view) so the fuzzy ranking lives in
     * [VersionMatch] rather than leaking into the registry.
     */
    public val labels: Set<String> get() = byLabel.keys

    /** Number of distinct version_codes indexed. */
    public val size: Int get() = byVersionCode.size

    /**
     * True when at least one `version_code` collision collapsed two input maps
     * into one index slot ([inputCount] > [size]) — an authoring mistake worth
     * surfacing rather than swallowing.
     */
    public val hasVersionCodeCollision: Boolean get() = inputCount > size

    public companion object {
        /** Build a registry from [maps] (varargs convenience). */
        public fun of(vararg maps: RosettaMap): MapRegistry = fromCollection(maps.asList())

        /**
         * Build a registry from [maps]: index every map by its `version_code`
         * (primary) and `version` label (fallback). FIRST-WINS per key — the
         * first map to claim a code (or label) keeps it. This is the
         * cross-client canonical collision policy that the frida twin
         * (`versionCodeIndex`, putIfAbsent) also implements, so a duplicate
         * `version_code` resolves to the same map on both clients.
         */
        public fun fromCollection(maps: Collection<RosettaMap>): MapRegistry {
            val byCode = LinkedHashMap<Long, RosettaMap>(maps.size)
            val byLabel = LinkedHashMap<String, RosettaMap>(maps.size)
            for (m in maps) {
                byCode.putIfAbsent(m.versionCode, m)
                byLabel.putIfAbsent(m.version, m)
            }
            return MapRegistry(byCode, byLabel, inputCount = maps.size)
        }
    }
}

/** How a [SelectedMap] was chosen from the registry. */
public enum class MatchedBy {
    /** Matched on the authoritative `version_code` key (the O(1), exact path). */
    VERSION_CODE,

    /** Matched on the exact `version` label fallback. */
    LABEL,

    /**
     * Matched on the closest `version` label via the opt-in fuzzy fallback
     * (`allowFuzzyMatch = true`) — NOT an exact match. Surfaced distinctly so a
     * caller can log / gate on a fuzzy pick (a wrong-version map silently
     * corrupts hooks).
     */
    FUZZY_LABEL,
}

/** A selection result, recording how the map was chosen. */
public data class SelectedMap(
    val map: RosettaMap,
    val matchedBy: MatchedBy,
)

public object VersionMatch {
    /**
     * Select the map for a detected app version.
     *
     * @param registry the available maps, indexed by version_code + label.
     * @param versionCode the detected `PackageInfo.versionCode`, if known —
     *   the authoritative, O(1) key.
     * @param versionLabel the detected `versionName`, for the fallback key.
     * @param allowFuzzyMatch OPT-IN fuzzy fallback (RFC 0001 Decision 3). When
     *   `true` AND both an exact version_code and an exact label match miss, the
     *   closest registered label (by component-wise semver distance) is selected
     *   ([MatchedBy.FUZZY_LABEL]). Off by default — an exact miss returns `null`
     *   so the caller fails loudly. A fuzzy pick requires a non-null
     *   [versionLabel] to compare against; with no label there is nothing to
     *   rank, so it stays `null`.
     * @return the selected map, or `null` if nothing matched.
     */
    public fun select(
        registry: MapRegistry,
        versionCode: Long? = null,
        versionLabel: String? = null,
        allowFuzzyMatch: Boolean = false,
    ): SelectedMap? {
        if (versionCode != null) {
            registry.byVersionCode(versionCode)?.let { return SelectedMap(it, MatchedBy.VERSION_CODE) }
        }
        if (versionLabel != null) {
            registry.byLabel(versionLabel)?.let { return SelectedMap(it, MatchedBy.LABEL) }
        }
        if (allowFuzzyMatch && versionLabel != null) {
            fuzzySelect(registry, versionLabel)?.let { return it }
        }
        return null
    }

    /**
     * The closest-label fuzzy fallback. Scans the registry's [MapRegistry.labels]
     * for the one nearest [versionLabel] by [versionDistance], breaking ties on
     * the lower version then the raw label string. Returns `null` only when the
     * registry has no labels at all.
     */
    private fun fuzzySelect(
        registry: MapRegistry,
        versionLabel: String,
    ): SelectedMap? {
        val target = parseVersion(versionLabel)
        // minByWithComparator over the label set; returns null only for an empty
        // registry (no labels to rank). `labels` IS the byLabel key set, so the
        // chosen label always has a non-null map — `getValue` documents that
        // invariant without an unreachable null-branch.
        val pickedLabel =
            registry.labels.minWithOrNull { a, b ->
                compareDistance(
                    versionDistance(target, parseVersion(a)),
                    versionDistance(target, parseVersion(b)),
                    a,
                    b,
                )
            } ?: return null
        return SelectedMap(registry.byLabel(pickedLabel)!!, MatchedBy.FUZZY_LABEL)
    }
}

/** `[major, minor, patch]`; missing components default to 0. */
private fun parseVersion(version: String): IntArray {
    // Cut at the first pre-release/build delimiter (`-`/`+`), then split on dots.
    val stripped = version.split('-', '+', limit = 2).first()
    val parts = stripped.split('.')
    return intArrayOf(numeric(parts.getOrNull(0)), numeric(parts.getOrNull(1)), numeric(parts.getOrNull(2)))
}

private fun numeric(component: String?): Int {
    if (component.isNullOrEmpty()) return 0
    return component.toIntOrNull() ?: 0
}

/**
 * Per-component absolute distance `[ |Δmajor|, |Δminor|, |Δpatch| ]`.
 *
 * Returned as a 3-vector (NOT a single weighted sum) so ranking is
 * lexicographic and cannot overflow a positional bucket — the xposed#13 bug
 * where `1.0.142` (sum 142) tied `1.1.42` (sum 142). Compared by
 * [compareDistance].
 */
private fun versionDistance(
    a: IntArray,
    b: IntArray,
): IntArray =
    intArrayOf(
        kotlin.math.abs(a[0] - b[0]),
        kotlin.math.abs(a[1] - b[1]),
        kotlin.math.abs(a[2] - b[2]),
    )

/**
 * Total order over candidates: lexicographic on the distance vector (major
 * difference dominates, then minor, then patch), then the LOWER parsed version,
 * then the raw label string — so the pick is deterministic even when two labels
 * parse to the same tuple (e.g. `1.0.0` vs `1.0.0-rc1`).
 */
private fun compareDistance(
    distA: IntArray,
    distB: IntArray,
    labelA: String,
    labelB: String,
): Int {
    for (i in 0..2) {
        val c = distA[i].compareTo(distB[i])
        if (c != 0) return c
    }
    // Equal distance: prefer the lower actual version, then the raw label.
    val va = parseVersion(labelA)
    val vb = parseVersion(labelB)
    for (i in 0..2) {
        val c = va[i].compareTo(vb[i])
        if (c != 0) return c
    }
    return labelA.compareTo(labelB)
}
