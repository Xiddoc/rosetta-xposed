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

/** A set of single-version maps, keyed by version label. */
public typealias MapRegistry = Map<String, RosettaMap>

/** A selection result, recording how the map was chosen. */
public data class SelectedMap(
    val map: RosettaMap,
    /** "version_code" | "label". */
    val matchedBy: String,
)

public object VersionMatch {
    /**
     * Select the map for a detected app version.
     *
     * @param registry the available maps (label → map).
     * @param versionCode the detected `PackageInfo.versionCode`, if known.
     * @param versionLabel the detected `versionName`, for the fallback key.
     * @return the selected map, or `null` if nothing matched.
     */
    public fun select(
        registry: MapRegistry,
        versionCode: Long? = null,
        versionLabel: String? = null,
    ): SelectedMap? {
        if (versionCode != null) {
            registry.values.firstOrNull { it.versionCode == versionCode }
                ?.let { return SelectedMap(it, "version_code") }
        }
        if (versionLabel != null) {
            registry[versionLabel]?.let { return SelectedMap(it, "label") }
        }
        return null
    }
}
