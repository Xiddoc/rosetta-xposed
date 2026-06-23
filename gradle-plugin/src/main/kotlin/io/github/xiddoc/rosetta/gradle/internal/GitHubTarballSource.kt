/*
 * The one network [MapsSource]: it pulls the whole repo tarball for a pinned
 * ref from GitHub's `codeload` endpoint and extracts just the JSON files that
 * live directly under `maps/<app>/`.
 *
 * Why the tarball and not the per-file `raw` URLs or the contents API: listing
 * "all versions under maps/<app>/" with the contents API needs an authenticated
 * call to dodge the unauthenticated rate limit (xposed#39 weighed raw vs.
 * tarball vs. archive). `codeload` is a single unauthenticated GET that yields
 * the full file set in one shot and is content-addressed when `ref` is a commit
 * SHA — the recommended pin for reproducibility/provenance.
 *
 * The actual byte download is an injected seam so the tarball-extraction logic
 * is unit tested against a hand-built archive, never the live network.
 */
package io.github.xiddoc.rosetta.gradle.internal

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

internal class GitHubTarballSource(
    private val download: (String) -> ByteArray = { url -> httpGet(url) },
) : MapsSource {
    override fun fetchAppMaps(
        repo: String,
        app: String,
        ref: String,
    ): FetchedMaps {
        val url = "https://codeload.github.com/$repo/tar.gz/$ref"
        val files = extractAppMaps(download(url), app)
        return FetchedMaps(ref, files)
    }

    /** Pulls every `<file>.json` directly under `maps/<app>/` (sans attestation sidecars). */
    private fun extractAppMaps(
        tarGz: ByteArray,
        app: String,
    ): List<RemoteMapFile> {
        val needle = "/maps/$app/"
        val out = mutableListOf<RemoteMapFile>()
        GZIPInputStream(ByteArrayInputStream(tarGz)).use { gz ->
            val tar = TarReader(gz)
            while (true) {
                val entry = tar.next() ?: break
                toMapFile(entry, needle)?.let { out.add(it) }
            }
        }
        return out.sortedBy { it.fileName }
    }

    /** An [entry] under `maps/<app>/`, or `null` if it is not a map file there. */
    private fun toMapFile(
        entry: TarEntry,
        needle: String,
    ): RemoteMapFile? {
        val at = entry.name.indexOf(needle)
        if (at < 0) return null
        val leaf = entry.name.substring(at + needle.length)
        if (leaf.contains('/')) return null // nested, not a map directly under <app>/
        if (!leaf.endsWith(".json") || leaf.endsWith(".json.att.json")) return null
        return RemoteMapFile(leaf, entry.data)
    }
}

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 60_000
private const val HTTP_OK = 200

private fun httpGet(url: String): ByteArray {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.connectTimeout = CONNECT_TIMEOUT_MS
    conn.readTimeout = READ_TIMEOUT_MS
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", "rosetta-xposed-gradle-plugin")
    conn.setRequestProperty("Accept", "application/x-gzip")
    try {
        val code = conn.responseCode
        check(code == HTTP_OK) { "GET $url failed: HTTP $code" }
        return conn.inputStream.use { it.readAllBytes() }
    } finally {
        conn.disconnect()
    }
}
