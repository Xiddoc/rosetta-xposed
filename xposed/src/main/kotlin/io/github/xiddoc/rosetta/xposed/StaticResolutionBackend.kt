/*
 * Static backend — the default. Wraps the neutral `core` Resolver over a
 * pre-computed map selected by version_code. No DexKit, no scan: O(1).
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.resolver.DiscoveredClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedField
import io.github.xiddoc.rosetta.core.resolver.ResolvedMethod
import io.github.xiddoc.rosetta.core.resolver.Resolver

public class StaticResolutionBackend(
    public val map: RosettaMap,
) : OverridableBackend {
    private val resolver = Resolver(map)

    override fun canResolve(realClass: String): Boolean = resolver.hasClass(realClass)

    /**
     * Register a runtime [discovered] write-back as a resolver override, so the
     * NEXT lookup of its real name is an O(1) static hit. The composite backend
     * uses this to feed a dynamically-discovered entry back into the static
     * path (RFC 0001 Decision 2 — the self-healing write-back).
     */
    override fun override(discovered: DiscoveredClass): Unit = resolver.override(discovered)

    override fun resolveClass(realClass: String): ResolvedClass = resolver.resolveClass(realClass)

    override fun resolveMethod(
        realClass: String,
        realMethod: String,
        argTypes: List<String>?,
    ): ResolvedMethod = resolver.resolveMethod(realClass, realMethod, argTypes)

    override fun resolveField(
        realClass: String,
        realField: String,
    ): ResolvedField = resolver.resolveField(realClass, realField)
}
