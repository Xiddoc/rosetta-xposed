/*
 * Static backend — the default. Wraps the neutral `core` Resolver over a
 * pre-computed map selected by version_code. No DexKit, no scan: O(1).
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.policy.TargetPolicy
import io.github.xiddoc.rosetta.core.resolver.DiscoveredClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedField
import io.github.xiddoc.rosetta.core.resolver.ResolvedMethod
import io.github.xiddoc.rosetta.core.resolver.Resolver

public class StaticResolutionBackend(
    public val map: RosettaMap,
    /**
     * The C1 target namespace [policy] (xposed#11). It MUST match the policy the
     * `:xposed` [TargetLoader] enforces, because the `:core` [Resolver] now runs
     * the namespace guard at the single resolve chokepoint — a custom
     * `allow`-list / `appNamespaceLabels` set here keeps the two layers in sync,
     * so a target the binding would allow is not pre-emptively denied by `:core`.
     */
    policy: TargetPolicy = TargetPolicy(),
) : OverridableBackend {
    private val resolver = Resolver(map, policy)

    override fun canResolve(realClass: String): Boolean = resolver.hasClass(realClass)

    /**
     * Translate a single type name real → obf through this backend's map (a
     * primitive / unmapped framework type passes through). Exposed so a sibling
     * backend (e.g. the dynamic discovery backend) can translate caller-supplied
     * real-name `argTypes` against the SAME map the static resolver uses, rather
     * than forking the translation. Delegates to [Resolver.translateType].
     */
    public fun translateType(typeName: String): String = resolver.translateType(typeName)

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
