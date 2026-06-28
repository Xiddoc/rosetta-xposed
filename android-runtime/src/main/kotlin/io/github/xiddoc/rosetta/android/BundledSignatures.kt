/*
 * Loads the community SIGNATURES that are BUNDLED INTO THE MODULE APK at build
 * time (the signature sibling of [BundledMaps]).
 *
 * The `io.github.xiddoc.rosetta.maps` Gradle plugin fetches the app's
 * `signatures/<app>/signatures.yaml` from rosetta-maps, converts it to JSON,
 * and bakes it under `src/main/resources/signatures/<app>.json` — packaged as a
 * plain Java resource on the MODULE's class loader. Reading via the class loader
 * (not `context.getAssets()`, which is the victim's) is the reliable
 * cross-process way to reach a bundled resource inside the hooked app, exactly
 * like [BundledMaps].
 *
 * Pure JVM (class loader + `SignatureLoader`), so it lives in the gated
 * :android-runtime module and is fully unit-tested. Feed the result to
 * `RosettaXposed.fromMapWithSignatures(map, index, BundledSignatures.load(app), …)`
 * to self-heal versions that have no bundled map.
 */
package io.github.xiddoc.rosetta.android

import io.github.xiddoc.rosetta.core.signature.SignatureLoader
import io.github.xiddoc.rosetta.core.signature.SignatureSet

/** Loads community signatures bundled as Java resources under `signatures/`. */
public object BundledSignatures {
    /**
     * Loads the bundled signatures for [app] (e.g. `"com.ticktick.task"`) from
     * `signatures/<app>.json` on the given [classLoader], defaulting to the one
     * that loaded this class (the module's own class loader inside the app
     * process).
     *
     * @throws IllegalStateException if no such resource exists on [classLoader].
     */
    public fun load(
        app: String,
        classLoader: ClassLoader = BundledSignatures::class.java.classLoader,
    ): SignatureSet {
        val path = "signatures/$app.json"
        val text =
            (
                classLoader.getResourceAsStream(path)
                    ?: error("rosetta-android: bundled signatures '$path' not found on the module class path")
            ).use { it.readBytes().decodeToString() }
        return SignatureLoader.fromJson(text)
    }
}
