/*
 * The signatures side of a fetch: the ref-keyed cache read/write of the raw
 * `signatures.yaml`, plus the build-time convert → validate → bake of the
 * runtime-loadable `<app>.json`. Split out of [RosettaMapsFetcher] so the
 * maps concern (filter/select/manifest) and the signatures concern stay
 * cohesive and each is independently unit-testable — the same separation the
 * `:xposed` side draws between `SignatureCompiler` (assembly) and
 * `AnchorClassifier` (interpretation).
 */
package io.github.xiddoc.rosetta.gradle.internal

import io.github.xiddoc.rosetta.core.signature.SignatureLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Caches and bakes the app's community signatures. The raw YAML is cached
 * alongside the maps under the same ref-keyed cache root (so warm/offline
 * builds reuse it), and — when enabled — converted to the JSON the runtime
 * [SignatureLoader] reads and validated with that real loader before it is
 * written into the generated `signatures/` resources dir.
 */
internal class SignatureBaker(
    private val log: (String) -> Unit = {},
) {
    /** The cached signatures YAML for this ref, or null if none was cached. */
    fun readCached(cacheRoot: Path): ByteArray? {
        val sig = cacheRoot.resolve(CACHE_FILE)
        return if (sig.isDirectory() || !Files.exists(sig)) null else sig.readBytes()
    }

    /** Cache the raw YAML next to the maps; a null payload (no signatures) is a no-op. */
    fun writeCached(
        cacheRoot: Path,
        yaml: ByteArray?,
    ) {
        if (yaml == null) return
        cacheRoot.createDirectories()
        cacheRoot.resolve(CACHE_FILE).writeBytes(yaml)
    }

    /**
     * When enabled and the app publishes a `signatures.yaml`, convert it to the
     * JSON the runtime [SignatureLoader] reads, VALIDATE it with the real loader
     * (a malformed/over-bound signatures file fails the build here, not on a
     * device), and write `<signaturesOutputDir>/<app>.json`. Returns whether a
     * file was written; a missing signatures file is a soft skip (not every app
     * publishes one).
     */
    fun bake(
        signatureYaml: ByteArray?,
        config: FetchConfig,
    ): Boolean {
        if (!config.fetchSignatures) return false
        val outDir = config.signaturesOutputDir ?: return false
        if (signatureYaml == null) {
            log("rosetta-maps: no signatures published for ${config.app} — skipping (maps only)")
            return false
        }
        val jsonText = SignatureYamlConverter.toJson(signatureYaml.decodeToString())
        // Validate with the REAL loader so a corrupt signatures file fails the
        // build loudly here rather than silently baking an unloadable resource.
        runCatching { SignatureLoader.fromJson(jsonText) }
            .onFailure { error("fetched signatures for ${config.app} failed validation: ${it.message}") }
        clean(outDir)
        outDir.createDirectories()
        outDir.resolve("${config.app}.json").writeBytes(jsonText.encodeToByteArray())
        return true
    }

    /** Drop any stale baked signatures so a re-fetch never leaves an old `<app>.json` behind. */
    private fun clean(outDir: Path) {
        if (!outDir.isDirectory()) return
        outDir.listDirectoryEntries().forEach { entry ->
            if (!entry.isDirectory() && entry.extension == "json") entry.deleteExisting()
        }
    }

    private companion object {
        const val CACHE_FILE = "signatures.yaml"
    }
}
