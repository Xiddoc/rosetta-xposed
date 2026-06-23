/*
 * The pure build-time orchestration behind the `fetchRosettaMaps` task: obtain
 * the published maps for an app (from a [MapsSource], with a ref-keyed cache for
 * warm/offline builds), filter them to the requested versions and to the schema
 * version THIS client accepts, validate the survivors with the real [MapLoader],
 * then write them plus an `index.json` manifest into the output dir.
 *
 * Everything here is plain JVM file/JSON/crypto work behind the [MapsSource]
 * seam, so it is fully unit testable without an emulator OR the network — which
 * is exactly the issue's "fetch/cache/filter/manifest logic is pure build-time
 * JVM, tested without a device" acceptance line.
 */
package io.github.xiddoc.rosetta.gradle.internal

import io.github.xiddoc.rosetta.core.MapLoader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/** Inputs for one fetch — resolved from the Gradle extension/task. */
@Suppress("LongParameterList") // a flat value holder; grouping these would only add ceremony
internal class FetchConfig(
    val repo: String,
    val app: String,
    val ref: String,
    /** Empty means "all versions published under `maps/<app>/`". */
    val versions: List<Long>,
    val offline: Boolean,
    val outputDir: Path,
    val cacheDir: Path,
    val currentSchemaVersion: Int,
)

/** What a fetch did — surfaced for the task's lifecycle log and for tests. */
internal class FetchSummary(
    val written: List<Long>,
    val skippedSchema: List<Long>,
    val fromCache: Boolean,
)

internal class RosettaMapsFetcher(
    private val source: MapsSource,
    private val log: (String) -> Unit = {},
) {
    fun fetch(config: FetchConfig): FetchSummary {
        require(config.app.isNotBlank()) { "rosettaMaps.app must be set" }
        require(config.ref.isNotBlank()) {
            "rosettaMaps.ref must be set — pin a commit SHA or tag for reproducible, auditable builds"
        }

        val (files, fromCache) = obtain(config)
        if (files.isEmpty()) {
            error("no maps found under maps/${config.app}/ in ${config.repo} at ref ${config.ref}")
        }

        val parsed = files.map { it to HEADER_JSON.decodeFromString<MapHeader>(it.bytes.decodeToString()) }
        val selected = select(parsed, config)
        val (matching, mismatched) = selected.partition { it.second.schemaVersion == config.currentSchemaVersion }
        mismatched.forEach { (file, header) ->
            log(
                "rosetta-maps: skipping ${file.fileName} (schema_version ${header.schemaVersion} " +
                    "!= client ${config.currentSchemaVersion})",
            )
        }

        write(matching, config)
        return FetchSummary(
            written = matching.map { it.second.versionCode }.sorted(),
            skippedSchema = mismatched.map { it.second.versionCode }.sorted(),
            fromCache = fromCache,
        )
    }

    // ---- obtain (cache + source) ------------------------------------------

    /**
     * Returns the raw map files plus whether they came from the cache. A pinned
     * ref is immutable content, so a warm cache for `(repo, ref, app)` is reused
     * even online (no re-fetch); offline REQUIRES that warm cache.
     */
    private fun obtain(config: FetchConfig): Pair<List<RemoteMapFile>, Boolean> {
        val cacheRoot = cacheRoot(config)
        val cached = readCache(cacheRoot)
        if (cached.isNotEmpty()) {
            log("rosetta-maps: using cached maps for ${config.app} at ${config.ref}")
            return cached to true
        }
        if (config.offline) {
            error(
                "offline mode: no cached maps for ${config.app} at ref ${config.ref} under $cacheRoot — " +
                    "run once online (or vendor the maps) to warm the cache",
            )
        }
        val fetched = source.fetchAppMaps(config.repo, config.app, config.ref)
        writeCache(cacheRoot, fetched.files)
        return fetched.files to false
    }

    private fun cacheRoot(config: FetchConfig): Path =
        config.cacheDir
            .resolve(config.repo.replace('/', '_'))
            .resolve(config.ref)
            .resolve(config.app)

    private fun readCache(cacheRoot: Path): List<RemoteMapFile> {
        if (!cacheRoot.isDirectory()) return emptyList()
        return cacheRoot
            .listDirectoryEntries("*.json")
            .filter { it.name.endsWith(".json") && !it.name.endsWith(".json.att.json") }
            .sortedBy { it.name }
            .map { RemoteMapFile(it.name, it.readBytes()) }
    }

    private fun writeCache(
        cacheRoot: Path,
        files: List<RemoteMapFile>,
    ) {
        cacheRoot.createDirectories()
        files.forEach { cacheRoot.resolve(it.fileName).writeBytes(it.bytes) }
    }

    // ---- select (versions subset) -----------------------------------------

    private fun select(
        parsed: List<Pair<RemoteMapFile, MapHeader>>,
        config: FetchConfig,
    ): List<Pair<RemoteMapFile, MapHeader>> {
        parsed.forEach { (file, header) ->
            check(file.fileName == "${header.versionCode}.json") {
                "map ${file.fileName} declares version_code ${header.versionCode} (filename must be <version_code>.json)"
            }
        }
        if (config.versions.isEmpty()) return parsed
        val byVersion = parsed.associateBy { it.second.versionCode }
        return config.versions.map { wanted ->
            byVersion[wanted]
                ?: error("requested version $wanted not published under maps/${config.app}/ at ref ${config.ref}")
        }
    }

    // ---- write (maps + index.json) ----------------------------------------

    private fun write(
        maps: List<Pair<RemoteMapFile, MapHeader>>,
        config: FetchConfig,
    ) {
        clean(config.outputDir)
        config.outputDir.createDirectories()
        // Validate with the REAL loader so a corrupt same-version map fails the
        // build loudly here rather than at runtime on a device.
        maps.forEach { (file, _) ->
            runCatching { MapLoader.fromJson(file.bytes.decodeToString()) }
                .onFailure { error("fetched map ${file.fileName} failed validation: ${it.message}") }
        }
        maps.forEach { (file, _) -> config.outputDir.resolve(file.fileName).writeBytes(file.bytes) }
        config.outputDir.resolve(INDEX_FILE).writeBytes(indexJson(maps, config).encodeToByteArray())
    }

    private fun clean(outputDir: Path) {
        if (!outputDir.isDirectory()) return
        outputDir.listDirectoryEntries().forEach { entry ->
            if (!entry.isDirectory() && (entry.extension == "json" || entry.name == INDEX_FILE)) {
                entry.deleteExisting()
            }
        }
    }

    private fun indexJson(
        maps: List<Pair<RemoteMapFile, MapHeader>>,
        config: FetchConfig,
    ): String {
        val obj =
            buildJsonObject {
                put("generated_by", "rosetta-xposed:fetchRosettaMaps")
                put("app", config.app)
                put("schema_version", config.currentSchemaVersion)
                putJsonObject("source") {
                    put("repo", config.repo)
                    put("ref", config.ref)
                }
                putJsonArray("maps") {
                    maps.sortedBy { it.second.versionCode }.forEach { (file, header) ->
                        addJsonObject {
                            put("version_code", header.versionCode)
                            put("file", file.fileName)
                            put("blob_sha", gitBlobSha(file.bytes))
                        }
                    }
                }
            }
        return INDEX_JSON.encodeToString(obj)
    }

    private companion object {
        const val INDEX_FILE = "index.json"
        val HEADER_JSON =
            Json {
                ignoreUnknownKeys = true
                isLenient = false
            }
        val INDEX_JSON = Json { prettyPrint = true }

        /** Git's blob object id: `SHA-1("blob <len>\0" + bytes)` — provenance per file. */
        fun gitBlobSha(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-1")
            md.update("blob ${bytes.size}".toByteArray(Charsets.US_ASCII))
            md.update(0)
            md.update(bytes)
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** The two header fields the fetcher reads leniently to filter/select a map. */
@Serializable
private class MapHeader(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("version_code") val versionCode: Long,
)
