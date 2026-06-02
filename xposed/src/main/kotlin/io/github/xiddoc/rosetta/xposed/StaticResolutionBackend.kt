/*
 * Static backend — the default. Wraps the neutral `core` Resolver over a
 * pre-computed map selected by version_code. No DexKit, no scan: O(1).
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.resolver.ResolvedClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedField
import io.github.xiddoc.rosetta.core.resolver.ResolvedMethod
import io.github.xiddoc.rosetta.core.resolver.Resolver

public class StaticResolutionBackend(
    public val map: RosettaMap,
) : ResolutionBackend {
    private val resolver = Resolver(map)

    override fun canResolve(realClass: String): Boolean = resolver.hasClass(realClass)

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
