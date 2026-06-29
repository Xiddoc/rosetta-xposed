/*
 * The `rosettaMaps { ... }` DSL. The developer declares WHAT they want (an app +
 * a pinned ref + an optional version subset) and the plugin fetches it at build
 * time; nothing here is read on-device.
 */
package io.github.xiddoc.rosetta.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

public abstract class RosettaMapsExtension {
    /** The app package whose maps to fetch, e.g. `"com.ticktick.task"`. Required. */
    public abstract val app: Property<String>

    /**
     * The rosetta-maps ref to pin to. **Required** — use a commit SHA or tag so
     * builds are reproducible and the source revision is auditable (a branch
     * name works but is mutable, so a warm cache may serve a stale snapshot).
     */
    public abstract val ref: Property<String>

    /** Version codes to fetch. Empty (default) means every version under `maps/<app>/`. */
    public abstract val versions: ListProperty<Long>

    /**
     * Also bake the app's community signatures (`signatures/<app>/signatures.yaml`,
     * converted to JSON at build time) so the module can self-heal UNMAPPED
     * versions via `RosettaXposed.fromMapWithSignatures(...)` / `BundledSignatures`.
     * Default `true`; a soft no-op when the app publishes no signatures. The
     * signatures come from the SAME fetch as the maps (no extra download) and
     * land in a `signatures/` resource subdir.
     */
    public abstract val signatures: Property<Boolean>

    /** The `owner/name` slug of the maps repo. Defaults to `Xiddoc/rosetta-maps`. */
    public abstract val repo: Property<String>

    /**
     * Reuse the ref-keyed cache without touching the network (hermetic / air-gapped
     * builds). Fails if the cache is cold. Default `false`.
     */
    public abstract val offline: Property<Boolean>

    /**
     * Write the fetched maps into the source tree ([vendorDirectory]) instead of a
     * generated dir, for fully committed / air-gapped builds. In vendor mode the
     * fetch is NOT wired into the build lifecycle — run `fetchRosettaMaps`
     * explicitly, then commit the result. Default `false`.
     */
    public abstract val vendor: Property<Boolean>

    /** Generated output dir (non-vendor). Defaults to `build/generated/rosetta-maps/maps`. */
    public abstract val outputDirectory: DirectoryProperty

    /** Where vendor mode writes maps. Defaults to `src/main/resources/maps`. */
    public abstract val vendorDirectory: DirectoryProperty

    /** Ref-keyed fetch cache. Defaults to `<gradleUserHome>/rosetta-maps-cache`. */
    public abstract val cacheDirectory: DirectoryProperty
}
