/*
 * DexKitIndex — the injection seam for the dynamic (self-healing) backend
 * (B.1, RFC 0001 Decision 2 / 5).
 *
 * This interface is the boundary between the device-free discovery LOGIC
 * (which ships now in [DynamicResolutionBackend]) and the real, device-only
 * DexKit-backed adapter (a thin follow-up; DexKit stays an optional later-phase
 * dependency per CLAUDE.md Decision 5).
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
 */
public data class MethodMatch(
    val declaringClass: String,
    val obfName: String,
    val descriptor: String,
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

    /** Find a class by its (obfuscated) superclass FQN; null on miss. */
    public fun findClassBySuperclass(superName: String): String?

    /** Find a single method matching [query]; null on miss / no unique match. */
    public fun findMethod(query: MethodQuery): MethodMatch?
}
