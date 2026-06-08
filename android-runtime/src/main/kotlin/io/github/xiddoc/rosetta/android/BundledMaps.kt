/*
 * Loads a Rosetta map that is BUNDLED INTO THE MODULE APK at build time
 * (RFC 0001 / CLAUDE.md: maps are baked into the artifact, never fetched on
 * device).
 *
 * The map lives under `src/main/resources/maps/<version_code>.json`, so it is
 * packaged as a plain Java resource on the MODULE's class loader. That matters:
 * inside the hooked app process, `context.getAssets()` is the VICTIM's assets,
 * not ours — but the module class loader still sees the module APK's Java
 * resources. Reading via the class loader is therefore the reliable
 * cross-process way to get at a bundled map, with no modulePath dance.
 *
 * This is the shipping, reusable form of the load-a-bundled-map step every
 * Xposed module would otherwise re-write by hand. It is pure JVM (class loader
 * + MapLoader), so it lives in the gated :android-runtime module and is fully
 * unit-tested.
 */
package io.github.xiddoc.rosetta.android

import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.model.RosettaMap

/** Loads `schema_version: 2` maps bundled as Java resources under `maps/`. */
public object BundledMaps {
    /**
     * Loads the bundled map named [fileName] (e.g. `"100.json"`) from
     * `maps/<fileName>` on the given [classLoader], defaulting to the one that
     * loaded this class (the module's own class loader inside the app process).
     *
     * @throws IllegalStateException if no such resource exists on [classLoader].
     */
    public fun load(
        fileName: String,
        classLoader: ClassLoader = BundledMaps::class.java.classLoader,
    ): RosettaMap {
        val path = "maps/$fileName"
        // Use `.use {}` so the stream is always closed even if readBytes()/
        // decodeToString() throws — a bare close() after the read would leak it.
        val text =
            (
                classLoader.getResourceAsStream(path)
                    ?: error("rosetta-android: bundled map '$path' not found on the module class path")
            ).use { it.readBytes().decodeToString() }
        return MapLoader.fromJson(text)
    }
}
