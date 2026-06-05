/*
 * DexKitBackedIndex — the REAL, device-side implementation of the
 * [io.github.xiddoc.rosetta.xposed.DexKitIndex] seam (RFC 0001 Decision 5).
 *
 * This is the ONLY file in the entire codebase that imports
 * `org.luckypray.dexkit`. The seam in :xposed speaks only plain-JVM value
 * types ([MethodQuery] / [MethodMatch]) and Strings, so :xposed (and :core)
 * keep building + unit-testing on a plain JVM with NO DexKit on the classpath
 * (a `FakeDexKitIndex` supplies canned answers there). This module is the thin
 * translation layer that turns those neutral calls into `DexKitBridge` queries
 * and wraps the results back into [MethodMatch].
 *
 * AMBIGUITY → null (fail-closed). Every class/method lookup collapses its
 * DexKit result list with `singleOrNull()`: a miss (empty) OR a non-unique
 * match (more than one) both map to `null`. This preserves the seam contract
 * the discovery backend relies on — the backend turns a `null` into a
 * fail-closed `DiscoveryException` and never poisons the cache with an
 * ambiguous half-match. It mirrors the `FakeDexKitIndex.singleOrNull()`
 * contract exactly, so the same discovery logic behaves identically over the
 * fake and the real bridge.
 *
 * All class results are OBFUSCATED, dotted, fully-qualified names (DexKit's
 * `ClassData.name` / `MethodData.className` are already dotted); the backend
 * pairs them with the requested real name.
 */
package io.github.xiddoc.rosetta.dexkit

import io.github.xiddoc.rosetta.xposed.DexKitIndex
import io.github.xiddoc.rosetta.xposed.MethodMatch
import io.github.xiddoc.rosetta.xposed.MethodQuery
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData

/**
 * A [DexKitIndex] backed by a live [DexKitBridge].
 *
 * The [bridge] is owned by the caller (it is [java.io.Closeable]; create it in
 * a `use { }` block and pass it in). This adapter never closes it. Calling any
 * adapter method AFTER the caller has closed the bridge is undefined behaviour
 * (the native handle is gone) — keep the adapter's lifetime within the bridge's.
 *
 * Note on matching facets: [MethodQuery.descriptor] is intentionally NOT
 * matched on here — only the return-type, parameter-type, and using-strings
 * facets drive [findMethod] (see its KDoc for why the JVM descriptor is not
 * decomposed).
 *
 * @property bridge the live DexKit bridge over the running app's dex files.
 */
public class DexKitBackedIndex(
    private val bridge: DexKitBridge,
) : DexKitIndex {
    /**
     * Find a binder stub by the AIDL interface descriptor STRING literal it
     * references (`Stub.DESCRIPTOR`). Non-unique / empty → `null`.
     *
     * Matched with [StringMatchType.Equals]: a descriptor is an exact literal,
     * so a substring (`Contains`, DexKit's default) match would wrongly also
     * select an unrelated class that merely embeds the descriptor in a larger
     * string, collapsing to a non-unique → `null` result. Exact match keeps the
     * discovery precise and fail-closed.
     */
    override fun findClassByAidlDescriptor(descriptor: String): String? =
        bridge
            .findClass {
                matcher { usingStrings(listOf(descriptor), StringMatchType.Equals) }
            }.singleOrNull()
            ?.name

    /**
     * Find the single class that references ALL of [anchors] (stable string
     * literals). Non-unique / empty → `null`.
     *
     * Matched with [StringMatchType.Equals] for the same precision reason as
     * [findClassByAidlDescriptor]: an anchor is a stable, exact literal the
     * class references, not a fuzzy substring.
     */
    override fun findClassByAnchors(anchors: List<String>): String? =
        bridge
            .findClass {
                matcher { usingStrings(anchors, StringMatchType.Equals) }
            }.singleOrNull()
            ?.name

    /**
     * Find the single class whose (obfuscated) superclass FQN is [superName].
     * Non-unique / empty → `null`.
     */
    override fun findClassBySuperclass(superName: String): String? =
        bridge
            .findClass {
                matcher { superClass(superName) }
            }.singleOrNull()
            ?.name

    /**
     * Find the single method on [MethodQuery.declaringClass] matching the
     * supplied facets (return type, parameter types, using-strings). The
     * scan is confined to the already-located declaring class so it has a
     * known starting point. Non-unique / empty → `null`.
     *
     * The JVM [MethodQuery.descriptor] is intentionally NOT decomposed here:
     * DexKit matches by dotted return/param type names, which the discovery
     * hints supply as [MethodQuery.returnType] / [MethodQuery.paramTypes].
     * A descriptor-only query (no return/param facets) therefore narrows by
     * declaring class alone and only resolves when the class has a single
     * candidate — the same fail-closed `singleOrNull()` contract.
     */
    override fun findMethod(query: MethodQuery): MethodMatch? =
        bridge
            .findMethod {
                matcher {
                    declaredClass(query.declaringClass)
                    query.returnType?.let { returnType(it) }
                    query.paramTypes?.let { paramTypes(it) }
                    if (query.usingStrings.isNotEmpty()) usingStrings(query.usingStrings, StringMatchType.Equals)
                }
            }.singleOrNull()
            ?.toMethodMatch()

    /**
     * Enumerate every method declared on the OBFUSCATED class [obfClass].
     * Empty on a miss (the class is not in the dex).
     */
    override fun membersOf(obfClass: String): List<MethodMatch> =
        bridge
            .getClassData(obfClass)
            ?.methods
            ?.map(MethodData::toMethodMatch)
            .orEmpty()
}

/** Wrap a DexKit [MethodData] into the seam's neutral [MethodMatch]. */
private fun MethodData.toMethodMatch(): MethodMatch =
    MethodMatch(
        declaringClass = className,
        obfName = methodName,
        descriptor = methodSign,
    )
