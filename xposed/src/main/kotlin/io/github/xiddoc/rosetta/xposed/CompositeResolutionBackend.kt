/*
 * CompositeResolutionBackend — static-first, dynamic-on-miss (B.1).
 *
 * This is the backend a self-healing module wires up: it answers from the
 * static map when it can, and only falls through to dynamic discovery
 * ([DynamicResolutionBackend]) when the static map has no entry for the real
 * name. The two backends are otherwise identical from the binding layer's
 * point of view — both speak the neutral `core` resolution types.
 *
 * WRITE-BACK (the self-healing loop, RFC 0001 Decision 2). On a successful
 * dynamic class discovery, the resolved [ClassEntry] is written back into the
 * static backend via [StaticResolutionBackend.override] (which calls the core
 * `Resolver.override`). The NEXT lookup of that real name is therefore an
 * O(1) static hit — the dynamic index is consulted at most once per real
 * class. Tests assert the fake index is not re-queried on a repeat lookup.
 *
 * FAIL-CLOSED. A dynamic miss surfaces as a [DiscoveryException] from the
 * dynamic backend; the composite does not swallow it. A partial discovery
 * never reaches write-back (the dynamic backend throws before returning), so
 * the static path is never poisoned with a half entry.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.resolver.ResolvedClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedField
import io.github.xiddoc.rosetta.core.resolver.ResolvedMethod

public class CompositeResolutionBackend internal constructor(
    private val static: OverridableBackend,
    private val dynamic: DiscoveringBackend,
) : ResolutionBackend {
    /** Resolvable if EITHER backend can answer for [realClass]. */
    override fun canResolve(realClass: String): Boolean = static.canResolve(realClass) || dynamic.canResolve(realClass)

    override fun resolveClass(realClass: String): ResolvedClass {
        if (static.canResolve(realClass)) return static.resolveClass(realClass)
        // Discover + surface the typed write-back payload, heal it into the
        // static backend, then serve the resolved class from the now-static
        // entry so every consumer reads one code path.
        val discovered = dynamic.discoverClass(realClass)
        static.override(discovered)
        return static.resolveClass(realClass)
    }

    override fun resolveMethod(
        realClass: String,
        realMethod: String,
        argTypes: List<String>?,
    ): ResolvedMethod {
        // Ensure the class is resolved (and written back) via the class path,
        // so a method lookup also heals the static map; then resolve through
        // whichever backend now owns the class.
        if (static.canResolve(realClass)) {
            return static.resolveMethod(realClass, realMethod, argTypes)
        }
        // Discover + write back the class first, then serve the method from the
        // now-static entry so overloads/caching go through one code path.
        resolveClass(realClass)
        return static.resolveMethod(realClass, realMethod, argTypes)
    }

    override fun resolveField(
        realClass: String,
        realField: String,
    ): ResolvedField {
        if (static.canResolve(realClass)) {
            return static.resolveField(realClass, realField)
        }
        resolveClass(realClass)
        return static.resolveField(realClass, realField)
    }
}
