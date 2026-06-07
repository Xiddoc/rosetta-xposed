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
 * threads. All caches and indices are [ConcurrentHashMap]s populated via
 * [ConcurrentHashMap.computeIfAbsent]: resolution is idempotent, so a benign
 * last-writer-wins on a race produces an equal value (no torn reads, no
 * `HashMap` resize corruption). [override] (the self-healing write path) takes a
 * short lock so its multi-map mutation is atomic with respect to readers.
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

    /** Guards the multi-map mutation in [override] so a reader never sees a half-applied re-point. */
    private val overrideLock = Any()

    /**
     * Reverse index: obfuscated class short name → real FQN, for tier-3
     * introspection.
     *
     * COLLISION POLICY — first-write-wins. Two real names that map to the same
     * obfuscated short name is a degenerate (usually invalid) map, but we make
     * the behaviour deterministic: the FIRST real name encountered owns the
     * reverse entry, and later collisions are ignored (rather than the previous
     * silent last-write-wins, which depended on map iteration order). A runtime
     * [override] is the one exception — it is an explicit, intentional re-point
     * and DOES take the obf entry, after cleaning the overridden real name's
     * previous (now stale) obf entry.
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
    ): ResolvedClass =
        // computeIfAbsent keeps the cache populate atomic + idempotent under
        // concurrent callers (xposed#10). The mapping function runs the C1
        // guard, which THROWS for a denied target — computeIfAbsent propagates
        // that and stores nothing, so a forbidden target is never cached
        // (no cache poisoning), exactly as the Frida resolver does.
        classCache.computeIfAbsent(realName) {
            val entry = prefetched ?: entryFor(realName)
            // (C1) Guard the target FQN BEFORE caching — a denied target must
            // throw before any downstream Class.forName / Java.use.
            TargetGuard.assertAllowed(realName, entry.obfuscated, appPrefix, policy)
            // `extends` is carried THROUGH UNTRANSLATED (xposed#12 decision).
            // The shared conformance fixture pins `extends` to the raw map value
            // and the Frida twin does the same, so translating it here would
            // diverge the two clients. The inherited-member walk that #12 needs
            // is done in the `:xposed` binding off the LOADED runtime superclass
            // chain (`Class.superclass`), which is robust even when the map omits
            // the `extends` edge — so the resolver does not need a translated
            // parent name. See `Targets.MethodTarget.member` / `FieldTarget.field`.
            ResolvedClass(realName = realName, obfName = entry.obfuscated, extends = entry.extends)
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
                    missMessage(ResolveTarget.METHOD, "$className.$methodName"),
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
                aidlTxn = picked.aidlTxn,
                static = picked.static,
                synthetic = picked.synthetic,
                isConstructor = picked.isConstructor,
                allOverloads = ordered,
            )
        methodCache[key] = value
        return value
    }

    /** Resolve a field by real names. */
    public fun resolveField(
        className: String,
        fieldName: String,
    ): ResolvedField {
        val key = "$className.$fieldName"
        fieldCache[key]?.let { return it }

        // One entry lookup (override-first); warm the class cache from it.
        val classEntry = entryFor(className)
        val cls = resolveClass(className, classEntry)
        val entry =
            classEntry.fields?.get(fieldName)
                ?: throw ResolveException(
                    missMessage(ResolveTarget.FIELD, "$className.$fieldName"),
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
        fieldCache[key] = value
        return value
    }

    /**
     * Translate a single type name: a real name in the map → its obf short
     * name; a primitive or unmapped framework type passes through.
     */
    public fun translateType(typeName: String): String {
        overrides[typeName]?.let { return it.obfuscated }
        map.classes[typeName]?.let { return it.obfuscated }
        return typeName
    }

    /** Forcibly invalidate any cached resolution scoped to [realName]. */
    public fun invalidate(realName: String) {
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
            invalidate(discovered.realName)
        }

    /** Reverse-lookup an obfuscated class short name to its real FQN. */
    public fun reverseLookup(obfName: String): String? = reverseClassIndex[obfName]

    private fun missMessage(
        target: ResolveTarget,
        name: String,
    ): String = "rosetta-xposed: no ${target.name.lowercase()} mapping for '$name' in map for ${map.app}@${map.version}."
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
