/*
 * Loads a Rosetta map that is BUNDLED INTO THE MODULE APK at build time
 * (RFC 0001 / CLAUDE.md: maps are baked into the artifact, never fetched on
 * device).
 *
 * The map lives under `module/src/main/resources/maps/<version_code>.json`, so
 * it is packaged as a plain Java resource on the MODULE's class loader. That
 * matters: inside the hooked app process, `context.getAssets()` is the VICTIM's
 * assets, not ours — but the module class loader still sees the module APK's
 * Java resources. Reading via the class loader is therefore the reliable
 * cross-process way to get at a bundled map, with no modulePath dance.
 *
 * GAP THIS EXPOSES: there is no shipping `BundledMaps` helper in :xposed today
 * (it stays Android-free). Every module re-writes this. A candidate for a small
 * optional `:xposed-android` artifact later.
 */
package com.example.rosettamodule

import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.model.RosettaMap

internal object BundledMaps {
    fun load(fileName: String): RosettaMap {
        val path = "maps/$fileName"
        val text =
            BundledMaps::class.java.classLoader
                ?.getResourceAsStream(path)
                ?.use { it.readBytes().decodeToString() }
                ?: error("rosetta-example: bundled map '$path' not found on the module class path")
        return MapLoader.fromJson(text)
    }
}
