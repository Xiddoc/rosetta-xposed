/*
 * DiscoverySink — where the dynamic backend records what it discovered (B.1).
 *
 * When a strategy resolves a real name at runtime, the backend hands the
 * resulting [io.github.xiddoc.rosetta.core.model.ClassEntry] to a sink. The
 * sink's job is provenance + (later) persistence: it tags the entry as a
 * `rosetta-runtime-discovered` source at LOW confidence so a discovered name
 * is never silently mistaken for a vetted, high-confidence static mapping —
 * the same provenance the Frida side emits for its runtime-discovered entries.
 *
 * SCOPE. Only the in-memory recording sink ships now. A persistent / on-device
 * cache (keyed by version_code + a content version, à la WaEnhancer's
 * TABLE_VERSION) and an upstream-contribution path are deliberately left as the
 * interface only — they are device/IO concerns out of B.1's pure-JVM scope.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.Confidence
import io.github.xiddoc.rosetta.core.model.MapSource

/** The provenance tool name stamped on every runtime-discovered entry. */
public const val RUNTIME_DISCOVERED_TOOL: String = "rosetta-runtime-discovered"

/** A single recorded discovery: the real name and the entry resolved for it. */
public data class DiscoveredEntry(
    /** Real fully-qualified name that was discovered. */
    val realName: String,
    /** The resolved class entry (obfuscated name + members). */
    val entry: ClassEntry,
)

/**
 * Receives entries the dynamic backend discovers at runtime. Implementations
 * decide what to do with them (record in memory, persist, contribute
 * upstream); the backend only calls [record] on a fully-resolved entry.
 */
public interface DiscoverySink {
    /** Record a discovered [realName] → [entry] mapping. */
    public fun record(
        realName: String,
        entry: ClassEntry,
    )

    public companion object {
        /** A sink that discards everything — the default when none is supplied. */
        public val NOOP: DiscoverySink =
            object : DiscoverySink {
                override fun record(
                    realName: String,
                    entry: ClassEntry,
                ) {
                    // Intentionally no-op: discovery still feeds the resolver
                    // cache via the composite backend; recording is optional.
                }
            }
    }
}

/**
 * An in-memory [DiscoverySink] that retains every discovered entry and can
 * render the `rosetta-runtime-discovered` provenance for them.
 *
 * THREAD SAFETY. A self-healing module may discover from several hooked
 * threads concurrently (the dynamic backend runs inside the target app), so
 * the backing list is synchronized: [record] appends under the lock and
 * [entries] / [provenance] read a consistent snapshot under the same lock.
 */
public class MapDiscoverySink : DiscoverySink {
    private val lock = Any()
    private val recorded = mutableListOf<DiscoveredEntry>()

    override fun record(
        realName: String,
        entry: ClassEntry,
    ) {
        synchronized(lock) { recorded += DiscoveredEntry(realName, entry) }
    }

    /** A snapshot of everything discovered so far, in discovery order. */
    public fun entries(): List<DiscoveredEntry> = synchronized(lock) { recorded.toList() }

    /**
     * The provenance source describing this sink's discoveries — tagged
     * `rosetta-runtime-discovered` at [Confidence.LOW], with [MapSource.classes]
     * reflecting how many entries were recorded. Suitable for merging into a
     * map's `sources` when a discovered batch is contributed upstream.
     */
    public fun provenance(): MapSource =
        MapSource(
            tool = RUNTIME_DISCOVERED_TOOL,
            classes = synchronized(lock) { recorded.size },
            confidence = Confidence.LOW,
            notes = "discovered at runtime by the dynamic (DexKit) backend; unverified — LOW confidence.",
        )
}
