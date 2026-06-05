/*
 * Selecting a RosettaMap for the running app — Kotlin twin of the
 * version_code-primary selection in rosetta-frida's
 * `src/session/version-match.ts`.
 *
 * Selection precedence (RFC 0001 Decision 3):
 *   1. version_code — the authoritative, O(1) key. Exact, never fuzzy.
 *   2. version label — the fallback when no version_code is available or
 *      no map carries the detected code.
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
 * indices are last-write-wins on a duplicate key, surfaced via the size of the
 * source vs the index if a caller cares.
 */
public class MapRegistry private constructor(
    private val byVersionCode: Map<Long, RosettaMap>,
    private val byLabel: Map<String, RosettaMap>,
) {
    /** The map registered for [versionCode] (O(1)), or null. */
    public fun byVersionCode(versionCode: Long): RosettaMap? = byVersionCode[versionCode]

    /** The map registered for the version [label] (O(1)), or null. */
    public fun byLabel(label: String): RosettaMap? = byLabel[label]

    /** Number of distinct version_codes indexed. */
    public val size: Int get() = byVersionCode.size

    public companion object {
        /** Build a registry from [maps] (varargs convenience). */
        public fun of(vararg maps: RosettaMap): MapRegistry = fromCollection(maps.asList())

        /**
         * Build a registry from [maps]: index every map by its `version_code`
         * (primary) and `version` label (fallback). Last-write-wins per key.
         */
        public fun fromCollection(maps: Collection<RosettaMap>): MapRegistry {
            val byCode = LinkedHashMap<Long, RosettaMap>(maps.size)
            val byLabel = LinkedHashMap<String, RosettaMap>(maps.size)
            for (m in maps) {
                byCode[m.versionCode] = m
                byLabel[m.version] = m
            }
            return MapRegistry(byCode, byLabel)
        }
    }
}

/** How a [SelectedMap] was chosen from the registry. */
public enum class MatchedBy {
    /** Matched on the authoritative `version_code` key (the O(1), exact path). */
    VERSION_CODE,

    /** Matched on the `version` label fallback. */
    LABEL,
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
     * @return the selected map, or `null` if nothing matched.
     */
    public fun select(
        registry: MapRegistry,
        versionCode: Long? = null,
        versionLabel: String? = null,
    ): SelectedMap? {
        if (versionCode != null) {
            registry.byVersionCode(versionCode)?.let { return SelectedMap(it, MatchedBy.VERSION_CODE) }
        }
        if (versionLabel != null) {
            registry.byLabel(versionLabel)?.let { return SelectedMap(it, MatchedBy.LABEL) }
        }
        return null
    }
}
