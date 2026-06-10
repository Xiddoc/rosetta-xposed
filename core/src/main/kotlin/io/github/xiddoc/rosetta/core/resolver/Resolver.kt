/*
 * The Resolver — the core real → obf translation, in Kotlin.
 *
 * This is the framework-neutral resolution layer (layer 3 of RFC 0001),
 * a faithful twin of rosetta-frida's `src/resolver/resolver.ts`. The two
 * implementations are kept honest by one shared conformance suite, not by
 * shared runtime code.
 *
 * Lookup chain:
 *   1. Memoized cache (per instance)
 *   2. Mapping lookup (runtime overrides take precedence over the map)
 *   3. Throw [ResolveException] — the slot a future dynamic / self-healing
 *      backend plugs into (RFC 0001 Decision 2).
 *
 * The resolver is the only place that knows real ↔ obf translation; it
 * maintains a reverse index (obf short name → real FQN) for tier-3
 * introspection.
 */
package io.github.xiddoc.rosetta.core.resolver

import io.github.xiddoc.rosetta.core.AmbiguousOverloadException
import io.github.xiddoc.rosetta.core.ResolveException
import io.github.xiddoc.rosetta.core.ResolveTarget
import io.github.xiddoc.rosetta.core.UnknownArgTypeException
import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.MethodEntry
import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.policy.TargetGuard
import io.github.xiddoc.rosetta.core.policy.TargetPolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * The neutral real → obf resolver.
 *
 * THREAD SAFETY (xposed#10). An Xposed module runs inside the app JVM and hooks
 * dispatch on arbitrary threads, so a single [Resolver] is shared across
 * threads. All caches and indices are [ConcurrentHashMap]s: resolution is
 * idempotent, so a benign last-writer-wins on a race produces an equal value (no
 * torn reads, no `HashMap` resize corruption). [override] / [invalidate] (the
 * self-healing write path) take a short lock so the multi-map re-point is atomic
 * with respect to readers.
 *
 * CACHE COHERENCE ACROSS [override] (xposed#13). The 4-map re-point being atomic
 * is not enough on its own: a reader that read the OLD [entryFor] snapshot
 * OUTSIDE the lock could `put` its now-stale resolution AFTER a concurrent
 * [override]→[invalidate] cleared the cache, re-inserting a superseded value
 * (lost invalidation). To close that race the caches are gated by a [generation]
 * counter bumped under [overrideLock] on every [override]/[invalidate]: a reader
 * captures the generation BEFORE reading the entry and its guarded put
 * ([putIfCurrent]) is DROPPED when that captured generation is stale, so no
 * resolution computed against a superseded map ever survives in the cache. The
 * reader still RETURNS its computed value (best-effort self-healing); only the
 * caching is suppressed, and the next lookup recomputes against the live map.
 *
 * SECURITY — TARGET NAMESPACE GUARD (xposed#11, RFC 0001 C1). Every resolve
 * path that produces a target FQN (the obfuscated name a layer-4 binding would
 * hand to `Class.forName` / `Java.use`) runs [TargetGuard.assertAllowed] at this
 * single `:core` chokepoint BEFORE the value is cached or returned, matching the
 * Frida resolver. A standalone `:core` consumer therefore cannot bypass C1; the
 * `:xposed` binding's boot/system loader check is defense-in-depth on top.
 *
 * @param map the selected per-version map.
 * @param policy the C1 target namespace policy (default = the strict
 *   [TargetPolicy] defaults).
 * @param appPackage the app package the namespace prefix is derived from
 *   (defaults to [RosettaMap.app]); pass it explicitly only to override the
 *   prefix the map's `app` would imply.
 */
public class Resolver(
    private val map: RosettaMap,
    private val policy: TargetPolicy = TargetPolicy(),
    appPackage: String = map.app,
) {
    /** The app's own namespace prefix, derived once for the C1 guard. */
    private val appPrefix: String = TargetGuard.appPrefixOf(appPackage, policy)

    /** Runtime overrides — take precedence over [RosettaMap.classes]. */
    private val overrides = ConcurrentHashMap<String, ClassEntry>()

    private val classCache = ConcurrentHashMap<String, ResolvedClass>()
    private val methodCache = ConcurrentHashMap<String, ResolvedMethod>()
    private val fieldCache = ConcurrentHashMap<String, ResolvedField>()

    /** Guards the multi-map mutation in [override]/[invalidate] so a reader never sees a half-applied re-point. */
    private val overrideLock = Any()

    /**
     * Cache-coherence generation (xposed#13). Bumped under [overrideLock] on
     * every [override]/[invalidate]. A reader captures it BEFORE computing a
     * resolution and a guarded put ([putIfCurrent]) is suppressed when the
     * captured value is stale, so a snapshot read before a concurrent
     * [override] can never re-insert its superseded resolution. Read lock-free
     * (volatile) when capturing; only mutated under the lock.
     */
    @Volatile
    private var generation: Long = 0L

    /** The cache-coherence gate ([overrideLock] + a live-[generation] reader) shared by every guarded put. */
    private val cacheGate = CacheGate(overrideLock) { generation }

    /**
     * Reverse index: obfuscated class short name → real FQN, for tier-3
     * introspection.
     *
     * COLLISION POLICY — first-write-wins. This is the CANONICAL cross-client
     * policy (xposed#14 M2, orchestrator decision): both Rosetta resolvers — this
     * Kotlin one and the rosetta-frida TS twin — resolve an obfuscated-short-name
     * collision to the FIRST real name encountered. Two real names that map to the
     * same obfuscated short name is a degenerate (usually invalid) map, but we make
     * the behaviour deterministic: the FIRST real name encountered owns the
     * reverse entry, and later collisions are ignored (rather than a silent
     * last-write-wins, which would depend on map iteration order). The
     * "reverse index is first-write-wins on a build-time obf collision" test
     * (CoverageTest) pins this. A runtime [override] is the one
     * exception — it is an explicit, intentional re-point and DOES take the obf
     * entry, after cleaning the overridden real name's previous (now stale) obf
     * entry.
     */
    private val reverseClassIndex = ConcurrentHashMap<String, String>()

    /** The obf short name each real name currently owns, so [override] can clean its stale reverse entry. */
    private val forwardObfIndex = ConcurrentHashMap<String, String>()

    init {
        for ((realName, entry) in map.classes) {
            forwardObfIndex[realName] = entry.obfuscated
            // First-write-wins: do not clobber an obf already claimed by an
            // earlier real name (deterministic regardless of iteration order).
            reverseClassIndex.putIfAbsent(entry.obfuscated, realName)
        }
    }

    /** True if [realName] is a known class real-name (override or map). */
    public fun hasClass(realName: String): Boolean = overrides.containsKey(realName) || map.classes.containsKey(realName)

    /**
     * Resolve a class by real name. Pass [prefetched] to reuse a [ClassEntry]
     * the caller already fetched from [entryFor] (the method/field paths), so
     * the class cache is warmed without a SECOND override/map lookup; omit it
     * for the public class-resolution path.
     */
    public fun resolveClass(
        realName: String,
        prefetched: ClassEntry? = null,
    ): ResolvedClass {
        classCache[realName]?.let { return it }
        // Capture the generation BEFORE reading the entry (xposed#13): a put
        // computed from this snapshot is suppressed if a concurrent
        // override/invalidate has since bumped the generation.
        val gen = generation
        val entry = prefetched ?: entryFor(realName)
        // (C1) Guard the target FQN BEFORE caching — a denied target must throw
        // before any downstream Class.forName / Java.use, and stores nothing
        // (no cache poisoning), exactly as the Frida resolver does.
        TargetGuard.assertAllowed(realName, entry.obfuscated, appPrefix, policy)
        // `extends` is carried THROUGH UNTRANSLATED (xposed#12 decision). The
        // shared conformance fixture pins `extends` to the raw map value and the
        // Frida twin does the same, so translating it here would diverge the two
        // clients. The inherited-member walk that #12 needs is done in the
        // `:xposed` binding off the LOADED runtime superclass chain
        // (`Class.superclass`), which is robust even when the map omits the
        // `extends` edge — so the resolver does not need a translated parent
        // name. See `Targets.MethodTarget.member` / `FieldTarget.field`.
        val value = ResolvedClass(realName = realName, obfName = entry.obfuscated, extends = entry.extends)
        // Resolution is idempotent, so reuse an entry a racing reader already
        // installed (keeps reference identity stable) rather than clobbering it.
        return putIfCurrentGen(cacheGate, classCache, realName, value, gen)
    }

    /**
     * The backing [ClassEntry] for [realName] (override-first), or throw a
     * class [ResolveException]. Kept private so the full map entry never leaks
     * across the resolution boundary — [resolveMethod] / [resolveField] read
     * methods/fields through here rather than off a public [ResolvedClass].
     */
    private fun entryFor(realName: String): ClassEntry =
        overrides[realName] ?: map.classes[realName]
            ?: throw ResolveException(
                missMessage(ResolveTarget.CLASS, realName),
                realName,
                map.app,
                map.version,
                ResolveTarget.CLASS,
            )

    /**
     * Resolve a method by real names. When the real name has several
     * overloads, [argTypes] (real names + framework types) disambiguates.
     * If [argTypes] is null and there is exactly one overload, that one is
     * returned; otherwise ambiguity throws.
     */
    public fun resolveMethod(
        className: String,
        methodName: String,
        argTypes: List<String>? = null,
    ): ResolvedMethod {
        // Cache key: argTypes-bearing lookups are distinct from the <auto> one
        // so a later disambiguated call re-resolves rather than reusing it.
        val argsKey = if (argTypes != null) "|${argTypes.joinToString(",")}" else "|<auto>"
        val key = "$className#$methodName$argsKey"
        methodCache[key]?.let { return it }

        // Capture the generation BEFORE the entry snapshot (xposed#13) so a put
        // built from a pre-override entry is suppressed if a concurrent
        // override/invalidate bumped the generation.
        val gen = generation
        // One entry lookup (override-first); warm the class cache from it rather
        // than re-running the lookup inside resolveClass.
        val entry = entryFor(className)
        val cls = resolveClass(className, entry)
        // Resolve the nullable `MethodOverloads` first (absent methods map, or
        // no such method name), then read its non-null `entries`. Chaining
        // `?.entries` onto the safe-call would emit an unreachable null-branch,
        // since MethodOverloads.entries is non-nullable by construction.
        val methodOverloads =
            entry.methods?.get(methodName)
                ?: throw ResolveException(
                    missMessage(ResolveTarget.METHOD, methodName, className),
                    methodName,
                    map.app,
                    map.version,
                    ResolveTarget.METHOD,
                    className,
                )
        val overloads = methodOverloads.entries

        val picked =
            if (argTypes == null) {
                overloads.singleOrNull()
                    ?: throw AmbiguousOverloadException(
                        "rosetta-xposed: method '$className.$methodName' has " +
                            "${overloads.size} overloads — pass argTypes to disambiguate.",
                        methodName,
                        className,
                        overloads.size,
                    )
            } else {
                val wanted = argTypes.map { toJvmDescriptor(it) { n -> translateType(n) } }
                overloads.firstOrNull { parseSignatureArgs(it.signature) == wanted }
                    // Single throw: the helper decides between the precise
                    // unknown-arg-type error and the generic no-overload-matches
                    // one, so resolveMethod stays within the throw-count budget.
                    ?: throw overloadMissException(
                        OverloadMiss(className, methodName, map.app, map.version),
                        argTypes,
                        wanted,
                        overloads,
                        ::hasClass,
                    )
            }

        // Selected entry first so consumers can do allOverloads[0] safely.
        val ordered = listOf(picked) + overloads.filter { it !== picked }
        val value =
            ResolvedMethod(
                realName = methodName,
                obfName = picked.obfuscated,
                className = cls.obfName,
                signature = picked.signature,
                static = picked.static,
                synthetic = picked.synthetic,
                isConstructor = picked.isConstructor,
                allOverloads = ordered,
            )
        return putIfCurrentGen(cacheGate, methodCache, key, value, gen)
    }

    /** Resolve a field by real names. */
    public fun resolveField(
        className: String,
        fieldName: String,
    ): ResolvedField {
        val key = "$className.$fieldName"
        fieldCache[key]?.let { return it }

        // Capture the generation BEFORE the entry snapshot (xposed#13).
        val gen = generation
        // One entry lookup (override-first); warm the class cache from it.
        val classEntry = entryFor(className)
        val cls = resolveClass(className, classEntry)
        val entry =
            classEntry.fields?.get(fieldName)
                ?: throw ResolveException(
                    missMessage(ResolveTarget.FIELD, fieldName, className),
                    fieldName,
                    map.app,
                    map.version,
                    ResolveTarget.FIELD,
                    className,
                )
        val value =
            ResolvedField(
                realName = fieldName,
                obfName = entry.obfuscated,
                className = cls.obfName,
                type = entry.type,
                static = entry.static,
            )
        return putIfCurrentGen(cacheGate, fieldCache, key, value, gen)
    }

    /**
     * Translate a single type name: a real name in the map → its obf short
     * name; a primitive or unmapped framework type passes through.
     *
     * SECONDARY C1 VECTOR (RFC 0001 C1, parity with the Frida twin
     * `src/resolver/resolver.ts:438,443`). The arg-type → obf path also yields
     * a MAP-CONTROLLED target FQN that flows into a downstream
     * `Class.forName` / `Java.use` (via overload descriptors). So BOTH mapped
     * branches (override + map entry) run [TargetGuard.assertAllowed] on the
     * translated output before returning it — a denied-namespace translation
     * throws here, exactly as Frida does. The unmapped passthrough is the
     * caller's OWN input (not a map-controlled target), so it is left untouched.
     */
    public fun translateType(typeName: String): String {
        overrides[typeName]?.let {
            TargetGuard.assertAllowed(typeName, it.obfuscated, appPrefix, policy)
            return it.obfuscated
        }
        map.classes[typeName]?.let {
            TargetGuard.assertAllowed(typeName, it.obfuscated, appPrefix, policy)
            return it.obfuscated
        }
        return typeName
    }

    /**
     * Forcibly invalidate any cached resolution scoped to [realName].
     *
     * Taken under [overrideLock] and bumps the [generation] (xposed#13) so the
     * clear is linearizable with a concurrent reader's guarded put: a reader
     * that snapshotted the pre-invalidate generation has its put dropped by
     * [putIfCurrent], closing the lost-invalidation race. Reentrant — [override]
     * calls this while already holding the lock.
     */
    public fun invalidate(realName: String): Unit =
        synchronized(overrideLock) {
            generation += 1
            classCache.remove(realName)
            methodCache.keys.filter { it.startsWith("$realName#") }.forEach { methodCache.remove(it) }
            fieldCache.keys.filter { it.startsWith("$realName.") }.forEach { fieldCache.remove(it) }
        }

    /**
     * Register a runtime override from a typed [DiscoveredClass] write-back
     * (e.g. a self-healing dynamic backend). The next lookup of
     * [DiscoveredClass.realName] is an O(1) static hit. Only the fields the
     * resolver re-resolves by are carried in — provenance stays with the
     * discovery sink, never the resolver.
     */
    public fun override(discovered: DiscoveredClass): Unit =
        // The override mutates four maps; take the lock so the multi-map
        // re-point is atomic w.r.t. another override and never leaves a reader
        // observing a half-applied state (xposed#10). Reads stay lock-free.
        synchronized(overrideLock) {
            val entry =
                ClassEntry(
                    obfuscated = discovered.obfName,
                    extends = discovered.extends,
                    methods = discovered.methods,
                    fields = discovered.fields,
                )
            overrides[discovered.realName] = entry

            // Clean the stale reverse entry: if this real name previously owned a
            // (different) obf short name in the reverse index, drop it so a lookup
            // of the OLD obf no longer resolves to this real name.
            val previousObf = forwardObfIndex[discovered.realName]
            if (previousObf != null && previousObf != entry.obfuscated && reverseClassIndex[previousObf] == discovered.realName) {
                reverseClassIndex.remove(previousObf)
            }
            // An override is an intentional re-point, so it takes the obf entry
            // (last-write-wins for this one obf) — the documented exception to the
            // first-write-wins build policy.
            reverseClassIndex[entry.obfuscated] = discovered.realName
            forwardObfIndex[discovered.realName] = entry.obfuscated
            // Bumps the generation (xposed#13) and clears stale caches, both
            // under this same (reentrant) lock — so a reader holding a pre-
            // override snapshot cannot re-insert a superseded resolution.
            invalidate(discovered.realName)
        }

    /** Reverse-lookup an obfuscated class short name to its real FQN. */
    public fun reverseLookup(obfName: String): String? = reverseClassIndex[obfName]

    /**
     * Canonical miss message — mirrors the rosetta-frida twin
     * (`resolver.ts#missMessage`): `<kind> '<name>' not found in map for
     * <app>@<version>`. For a member miss ([className] non-null) the declaring
     * class is named too — `<kind> '<name>' not found on class '<class>' …` —
     * the canonical "on class '<class>'" detail (xposed#32). The two clients
     * keep the same wording so a hook author reads identical diagnostics
     * whichever resolver fired.
     */
    private fun missMessage(
        target: ResolveTarget,
        name: String,
        className: String? = null,
    ): String {
        val on = if (className != null) " on class '$className'" else ""
        return "rosetta-xposed: ${target.name.lowercase()} '$name' not found$on in map for ${map.app}@${map.version}."
    }
}

/**
 * The cache-coherence gate (xposed#13): the lock that serialises the resolver's
 * `override`/`invalidate` against cache installs, plus a reader for the live
 * generation. Bundled into one value so [putIfCurrentGen] stays under the
 * parameter budget and the gate's two halves can never be passed mismatched.
 */
private class CacheGate(
    val lock: Any,
    val currentGen: () -> Long,
)

/**
 * Generation-gated cache install (xposed#13). Under [gate].lock (so it is
 * linearizable with the resolver's `override`/`invalidate` generation bump +
 * cache clear, which run under the same lock), install [value] for [key] in
 * [cache] ONLY when the captured [gen] still equals the live generation
 * ([gate].currentGen); otherwise DROP the put and return [value] uncached — so a
 * resolution computed against a now-superseded map snapshot never survives in
 * the cache (the lost-invalidation race). `putIfAbsent` preserves a value a
 * racing reader already installed under the same generation (resolution is
 * idempotent, so this keeps a stable reference identity).
 *
 * File-private (the gate is passed in) so it does not count against
 * [Resolver]'s method budget.
 */
private inline fun <V : Any> putIfCurrentGen(
    gate: CacheGate,
    cache: ConcurrentHashMap<String, V>,
    key: String,
    value: V,
    gen: Long,
): V =
    synchronized(gate.lock) {
        if (gen != gate.currentGen()) value else cache.putIfAbsent(key, value) ?: value
    }

/** The (className, methodName, app, version) context for an overload-miss message. */
private data class OverloadMiss(
    val className: String,
    val methodName: String,
    val app: String,
    val version: String,
)

/**
 * Build the exception for an overload disambiguation miss: a precise
 * [UnknownArgTypeException] when an arg type is an unmapped class the overloads
 * don't even declare, otherwise the generic no-overload-matches
 * [ResolveException]. File-private so [Resolver] stays small and resolveMethod
 * keeps a single throw site.
 */
private fun overloadMissException(
    ctx: OverloadMiss,
    argTypes: List<String>,
    wanted: List<String>,
    overloads: List<MethodEntry>,
    knownClass: (String) -> Boolean,
): ResolveException {
    unknownArgTypeOrNull(argTypes, wanted, overloads, knownClass)?.let { argType ->
        return UnknownArgTypeException(
            "rosetta-xposed: arg type '$argType' (for '${ctx.className}.${ctx.methodName}') is not a known " +
                "class in the map for ${ctx.app}@${ctx.version}; no overload declares it either. " +
                "Map the class or pass a type the map knows.",
            ctx.methodName,
            ctx.app,
            ctx.version,
            ctx.className,
            argType,
        )
    }
    return ResolveException(
        "rosetta-xposed: no overload of '${ctx.className}.${ctx.methodName}' matches arg " +
            "types [${argTypes.joinToString(", ")}] in map for ${ctx.app}@${ctx.version}.",
        ctx.methodName,
        ctx.app,
        ctx.version,
        ResolveTarget.METHOD,
        ctx.className,
    )
}

/**
 * Return the first arg type that is an unmapped class-name form whose translated
 * descriptor appears in NO overload, or null when every arg type is either a
 * known class, a primitive/array/raw descriptor, or a descriptor some overload
 * declares (a legitimate disambiguation miss, not an unmapped type).
 *
 * HEURISTIC — [isClassNameForm] decides "this arg type is a dotted class name
 * the caller expected the map to know," as opposed to a raw descriptor /
 * primitive / array the caller passed verbatim. It does so by EXCLUSION (an arg
 * type is a class-name form only if it is none of the descriptor/primitive
 * forms), because a class name has no positive marker — `com.example.Foo`,
 * `IFoo`, and a bare `Foo` are all valid. The excluded forms are:
 *   - `L…` raw object descriptor and `[…` raw array descriptor (already-encoded
 *     forms the caller chose to pass directly);
 *   - `…[]` source-style array (handled by [toJvmDescriptor], not a class name);
 *   - a named primitive (`int`, `boolean`, …) per [Descriptors.primitive];
 *   - a single character — this excludes a bare descriptor LETTER (`I`, `Z`,
 *     `L`, …). It is safe because no real Java type name is one character long,
 *     so it never excludes a legitimate class name; the length check is purely
 *     "is this a lone descriptor letter," not an arbitrary length cap.
 *
 * KEEP IN SYNC — this function is DUPLICATED by value in two languages:
 * rosetta-xposed (Kotlin, here) and rosetta-frida (TS, the
 * `unknownArgTypeOrNull` twin in `src/resolver/resolver.ts`). The duplication
 * is intentional (each client stays pure-JVM / pure-TS with no shared
 * runtime), so the two copies MUST make the same distinction — it drives WHICH
 * exception fires (precise [UnknownArgTypeException] vs generic no-overload
 * [ResolveException]) — or the clients raise different errors for the same
 * input. The shared conformance fixtures (`errors.json`, `methods.json`,
 * `overloads.json`) PIN the boundary cases. Changing the heuristic here
 * requires: (a) making the same change in the Frida twin, (b) verifying the
 * conformance fixtures still cover the new boundary, and (c) adding a fixture
 * case (byte-identical in both repos, with both sha256 manifests regenerated)
 * if they do not.
 */
private fun unknownArgTypeOrNull(
    argTypes: List<String>,
    wanted: List<String>,
    overloads: List<MethodEntry>,
    knownClass: (String) -> Boolean,
): String? {
    val knownDescriptors: Set<String> = overloads.flatMapTo(mutableSetOf()) { parseSignatureArgs(it.signature) }
    argTypes.forEachIndexed { i, argType ->
        val isClassNameForm =
            !argType.startsWith("L") &&
                !argType.startsWith("[") &&
                !argType.endsWith("[]") &&
                Descriptors.primitive(argType) == null &&
                // Exclude a lone descriptor letter (I/Z/L/…); no real type name
                // is one character, so this never drops a legitimate class name.
                argType.length != 1
        if (isClassNameForm && !knownClass(argType) && wanted[i] !in knownDescriptors) return argType
    }
    return null
}
