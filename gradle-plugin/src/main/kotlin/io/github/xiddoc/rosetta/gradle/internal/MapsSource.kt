/*
 * The fetch SEAM: where the maps come from, decoupled from HOW they are
 * filtered, validated, cached, and written ([RosettaMapsFetcher]).
 *
 * Splitting this out is what keeps the issue's "fetch/cache/filter/manifest
 * logic is pure build-time JVM" promise testable: the orchestration is unit
 * tested against a fake [MapsSource], and the one network implementation
 * ([GitHubTarballSource]) is unit tested against a hand-built tarball through
 * its own injected download seam — so neither test touches the network.
 */
package io.github.xiddoc.rosetta.gradle.internal

/** One fetched map file's name (`<version_code>.json`) and raw bytes. */
internal class RemoteMapFile(
    val fileName: String,
    val bytes: ByteArray,
)

/** The maps fetched for one app at one ref, plus the ref they came from. */
internal class FetchedMaps(
    val ref: String,
    val files: List<RemoteMapFile>,
)

/** Fetches the published map JSON for an app at a pinned ref. */
internal interface MapsSource {
    /**
     * Returns every `maps/<app>/<version_code>.json` published in [repo] at
     * [ref] (a commit SHA, tag, or branch). Attestation sidecars
     * (`<version_code>.json.att.json`) are excluded.
     */
    fun fetchAppMaps(
        repo: String,
        app: String,
        ref: String,
    ): FetchedMaps
}
