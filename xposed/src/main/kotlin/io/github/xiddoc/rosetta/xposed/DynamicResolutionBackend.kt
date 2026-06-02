/*
 * Dynamic backend — on-device self-healing via DexKit (RFC 0001 Decision 2).
 *
 * STATUS: architected, not yet built. This is the fallback used when no
 * static map exists for the running version_code: run the DexKit-dialect
 * signatures live, resolve the names, emit them as a
 * `rosetta-runtime-discovered` source, cache locally (keyed by version_code
 * + a content version, à la WaEnhancer's TABLE_VERSION), and optionally
 * contribute upstream — the "slowly uncover names from real users" loop,
 * automated.
 *
 * When implemented, this module will gain an OPTIONAL `org.luckypray:dexkit`
 * dependency and an `libdexkit.so`; static-only modules never ship it. The
 * shape is fixed here so the binding layer and backend selection can be
 * written and tested against the interface today.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.resolver.ResolvedClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedField
import io.github.xiddoc.rosetta.core.resolver.ResolvedMethod

/**
 * Placeholder for the DexKit-backed dynamic backend. Every entry point
 * throws [NotImplementedError] until Phase 2; it exists so callers can
 * compile against the final backend-selection shape now.
 */
public class DynamicResolutionBackend : ResolutionBackend {
    override fun canResolve(realClass: String): Boolean = false

    override fun resolveClass(realClass: String): ResolvedClass = notYet()

    override fun resolveMethod(
        realClass: String,
        realMethod: String,
        argTypes: List<String>?,
    ): ResolvedMethod = notYet()

    override fun resolveField(realClass: String, realField: String): ResolvedField = notYet()

    private fun notYet(): Nothing =
        throw NotImplementedError(
            "rosetta-xposed: the DexKit dynamic backend is planned for a later phase " +
                "(RFC 0001 Decision 2). Ship a static map for this version_code, or " +
                "register a runtime-discovered override via Resolver.override(...).",
        )
}
