/*
 * Resolution backends (RFC 0001 Decision 2).
 *
 * A single thin resolution API sits in front of two interchangeable
 * backends:
 *
 *   - StaticResolutionBackend (default) — a pre-computed map exists for
 *     this version_code, so resolution is an O(1) lookup with no DexKit,
 *     no native `.so`, and no on-device scan. This is the only backend the
 *     static-only module ships.
 *
 *   - DynamicResolutionBackend (fallback / self-healing) — no map for this
 *     version, so DexKit-dialect signatures run on-device, resolve the
 *     names, and emit the result as a `rosetta-runtime-discovered` source
 *     to be cached locally and optionally contributed upstream. Architected
 *     now, built in a later phase; DexKit stays an optional dependency.
 *
 * Both backends speak the same neutral `core` resolution types, so the
 * binding layer above them never cares which one answered.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.resolver.ResolvedClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedField
import io.github.xiddoc.rosetta.core.resolver.ResolvedMethod

/** Resolves real names to obfuscated ones for the running app version. */
public interface ResolutionBackend {
    /** True when this backend can answer for [realClass] without a miss. */
    public fun canResolve(realClass: String): Boolean

    public fun resolveClass(realClass: String): ResolvedClass

    public fun resolveMethod(
        realClass: String,
        realMethod: String,
        argTypes: List<String>? = null,
    ): ResolvedMethod

    public fun resolveField(realClass: String, realField: String): ResolvedField
}
