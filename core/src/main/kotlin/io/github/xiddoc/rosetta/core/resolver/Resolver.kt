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
import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.RosettaMap

public class Resolver(
    private val map: RosettaMap,
) {
    /** Runtime overrides — take precedence over [RosettaMap.classes]. */
    private val overrides = mutableMapOf<String, ClassEntry>()

    private val classCache = mutableMapOf<String, ResolvedClass>()
    private val methodCache = mutableMapOf<String, ResolvedMethod>()
    private val fieldCache = mutableMapOf<String, ResolvedField>()

    /** Reverse index: obfuscated class short name → real FQN. */
    private val reverseClassIndex = mutableMapOf<String, String>()

    init {
        for ((realName, entry) in map.classes) {
            reverseClassIndex[entry.obfuscated] = realName
        }
    }

    /** True if [realName] is a known class real-name (override or map). */
    public fun hasClass(realName: String): Boolean = overrides.containsKey(realName) || map.classes.containsKey(realName)

    /** Resolve a class by real name. */
    public fun resolveClass(realName: String): ResolvedClass {
        classCache[realName]?.let { return it }

        val entry = entryFor(realName)
        val value = ResolvedClass(realName = realName, obfName = entry.obfuscated, extends = entry.extends)
        classCache[realName] = value
        return value
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
                missMessage("class", realName),
                realName,
                map.app,
                map.version,
                "class",
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
        val key = methodCacheKey(className, methodName, argTypes)
        methodCache[key]?.let { return it }

        val cls = resolveClass(className)
        val entry = entryFor(className)
        // Resolve the nullable `MethodOverloads` first (absent methods map, or
        // no such method name), then read its non-null `entries`. Chaining
        // `?.entries` onto the safe-call would emit an unreachable null-branch,
        // since MethodOverloads.entries is non-nullable by construction.
        val methodOverloads =
            entry.methods?.get(methodName)
                ?: throw ResolveException(
                    missMessage("method", "$className.$methodName"),
                    methodName,
                    map.app,
                    map.version,
                    "method",
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
                    ?: throw ResolveException(
                        "rosetta-xposed: no overload of '$className.$methodName' matches arg " +
                            "types [${argTypes.joinToString(", ")}] in map for " +
                            "${map.app}@${map.version}.",
                        methodName,
                        map.app,
                        map.version,
                        "method",
                        className,
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
                static = picked.static == true,
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

        val cls = resolveClass(className)
        val classEntry = entryFor(className)
        val entry =
            classEntry.fields?.get(fieldName)
                ?: throw ResolveException(
                    missMessage("field", "$className.$fieldName"),
                    fieldName,
                    map.app,
                    map.version,
                    "field",
                    className,
                )
        val value =
            ResolvedField(
                realName = fieldName,
                obfName = entry.obfuscated,
                className = cls.obfName,
                type = entry.type,
                static = entry.static == true,
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
    public fun override(discovered: DiscoveredClass) {
        val entry =
            ClassEntry(
                obfuscated = discovered.obfName,
                extends = discovered.extends,
                methods = discovered.methods,
                fields = discovered.fields,
            )
        overrides[discovered.realName] = entry
        reverseClassIndex[entry.obfuscated] = discovered.realName
        invalidate(discovered.realName)
    }

    /** Reverse-lookup an obfuscated class short name to its real FQN. */
    public fun reverseLookup(obfName: String): String? = reverseClassIndex[obfName]

    private fun methodCacheKey(
        className: String,
        methodName: String,
        argTypes: List<String>?,
    ): String {
        val args = if (argTypes != null) "|${argTypes.joinToString(",")}" else "|<auto>"
        return "$className#$methodName$args"
    }

    private fun missMessage(
        target: String,
        name: String,
    ): String = "rosetta-xposed: no $target mapping for '$name' in map for ${map.app}@${map.version}."
}
