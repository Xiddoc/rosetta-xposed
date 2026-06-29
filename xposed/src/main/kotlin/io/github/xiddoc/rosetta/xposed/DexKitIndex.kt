/*
 * DexKitIndex — the injection seam for the dynamic (self-healing) backend
 * (B.1, RFC 0001 Decision 2 / 5).
 *
 * This interface is the boundary between the device-free discovery LOGIC
 * (in [DynamicResolutionBackend]) and the real, device-only DexKit-backed
 * adapter (the optional `:dexkit` module — built and integration-tested against
 * a committed DEX fixture; on-device native wiring is not yet proven end-to-end
 * on Android).
 *
 * DESIGN RULE — no DexKit types in these signatures. The seam speaks only
 * plain-JVM value types ([MethodQuery] / [MethodMatch]) and Strings, so:
 *
 *   1. `:xposed` compiles and unit-tests on a plain JVM with NO
 *      `org.luckypray:dexkit` on the classpath (a `FakeDexKitIndex` in tests
 *      supplies canned answers);
 *   2. the real adapter is a thin translation layer — it maps these calls to
 *      `DexKitBridge` queries and wraps results back into [MethodMatch] — and
 *      is the only file that ever imports a DexKit type.
 *
 * All class results are OBFUSCATED fully-qualified names (what the live app
 * actually exposes); the backend pairs them with the requested REAL name.
 */
package io.github.xiddoc.rosetta.xposed

/**
 * A signature-shaped method query, expressed in plain-JVM value types so the
 * seam carries no DexKit dependency.
 *
 * @property declaringClass OBFUSCATED FQN of the class to search within (the
 *   result of an earlier class-discovery strategy). The scan is confined to
 *   this class so the search has a known starting point.
 * @property descriptor JVM descriptor of the method's parameters + return,
 *   e.g. `(Landroid/os/Bundle;Lbbbb;)V`. Optional — omitted when only the
 *   other facets constrain the search.
 * @property returnType OBFUSCATED FQN (or JVM descriptor) of the return type,
 *   optional.
 * @property paramTypes OBFUSCATED FQNs (or descriptors) of the parameters,
 *   optional.
 * @property usingStrings stable string literals the target method references,
 *   used as a signature facet when names are unstable. Each is a contributor
 *   input and is bounded + RE2-validated via [SafePattern] before use.
 */
public data class MethodQuery(
    val declaringClass: String,
    val descriptor: String? = null,
    val returnType: String? = null,
    val paramTypes: List<String>? = null,
    val usingStrings: List<String> = emptyList(),
)

/**
 * A resolved method match — the obfuscated coordinates the backend needs to
 * build a [io.github.xiddoc.rosetta.core.model.MethodEntry].
 *
 * @property declaringClass OBFUSCATED FQN the method lives on.
 * @property obfName the method's OBFUSCATED short name (e.g. `c`, or
 *   `<init>` for a constructor).
 * @property descriptor the method's JVM descriptor signature (obfuscated
 *   class refs), e.g. `(Landroid/os/Bundle;)V`.
 * @property isSynthetic whether the method is compiler-synthesised — an
 *   `ACC_SYNTHETIC` / `ACC_BRIDGE` member (a covariant-return or generic
 *   bridge forwarder, an `access$NNN` accessor, etc.). The kept-name member
 *   harvest skips these so a bridge that shares the real method's name is not
 *   admitted as a phantom same-name overload (#47). Default `false`: a match
 *   from [findMethod] is a single targeted hit, so only [membersOf]'s bulk
 *   enumeration needs to populate it.
 */
public data class MethodMatch(
    val declaringClass: String,
    val obfName: String,
    val descriptor: String,
    val isSynthetic: Boolean = false,
)

/**
 * The device-side index abstraction. Implementations resolve obfuscation by
 * signature; the default one is DexKit-backed (device-only, deferred), and a
 * `FakeDexKitIndex` in tests returns canned answers so the discovery logic is
 * fully unit-testable.
 *
 * Every method returns a NULLABLE / possibly-empty result for a miss; the
 * backend (not the index) decides how a miss becomes a fail-closed
 * [DiscoveryException].
 */
public interface DexKitIndex {
    /** Find a class by its stable AIDL interface descriptor; null on miss. */
    public fun findClassByAidlDescriptor(descriptor: String): String?

    /**
     * Find a class that references ALL of [anchors] (stable string literals);
     * null on miss. The anchors are contributor input and must already have
     * passed [SafePattern] bounds.
     */
    public fun findClassByAnchors(anchors: List<String>): String?

    /**
     * Find a class that references a string constant matching EACH of
     * [patterns] as a regular expression; null on miss. Unlike
     * [findClassByAnchors] (exact-literal match), this matches string
     * CONSTANTS by regex — the on-device counterpart of a sigmatcher
     * `type: regex` signature whose pattern is a genuine regex (e.g. an
     * endpoint URL with a `.*` wildcard). The patterns are contributor input
     * and MUST already have passed [SafePattern.compileAll] (bounds + RE2
     * linear-time compile) before reaching here — the backend routes them
     * through that chokepoint so a pathological pattern can never reach a
     * backtracking engine. The DexKit-backed adapter matches with
     * `StringMatchType.SimilarRegex`.
     */
    public fun findClassByStringPatterns(patterns: List<String>): String?

    /** Find a class by its (obfuscated) superclass FQN; null on miss. */
    public fun findClassBySuperclass(superName: String): String?

    /** Find a single method matching [query]; null on miss / no unique match. */
    public fun findMethod(query: MethodQuery): MethodMatch?

    /**
     * All methods declared on the OBFUSCATED class [obfClass] (may be empty).
     *
     * Backs the dynamic backend's KEPT-NAME member harvest (strategy e,
     * #47): once a class is located, every member is keyed by its own
     * obfuscated short name, so a method R8 did NOT rename resolves by its real
     * (== obfuscated) name with no per-method signature. Empty on a miss (the
     * class is not in the dex).
     */
    public fun membersOf(obfClass: String): List<MethodMatch>
}
