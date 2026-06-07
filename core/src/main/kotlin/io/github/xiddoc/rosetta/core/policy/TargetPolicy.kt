/*
 * Target namespace guard (RFC 0001 C1, critical security fix) — the
 * framework-neutral decision core, in :core.
 *
 * THREAT. A community map maps a real name to an ARBITRARY obfuscated string,
 * and a layer-4 binding feeds that string verbatim into a reflective load
 * (`Class.forName(...)` / `Java.use(...)`) followed by `setAccessible(true)`.
 * A malicious or simply wrong map could therefore redirect a hook at a
 * sensitive framework class — `java.lang.Runtime`, `android.app.*`, a
 * `dagger.internal.Provider`, etc. — and the binding would happily load it
 * and make its members accessible.
 *
 * MITIGATION. A *target* (the FQN that would be passed to `Class.forName`:
 * `ResolvedClass.obfName` and `ResolvedMethod`/`ResolvedField.className`, plus
 * the mapped output of `translateType`) is confined to the app's own /
 * package-local namespace, with an explicit escape-hatch allowlist for
 * legitimate framework hooks. Anything else is rejected fail-closed — the
 * resolver THROWS [io.github.xiddoc.rosetta.core.TargetPolicyException] BEFORE
 * the class load and before `setAccessible`. There is no warn-and-proceed mode
 * (strict only).
 *
 * WHY :core OWNS THIS (xposed#11). The C1 control was previously enforced only
 * in the `:xposed` binding layer (`Targets.loadGuardedClass`), so a standalone
 * `:core` `Resolver` consumer bypassed the documented security control
 * (fail-open). The guard now lives at the single `:core` resolve chokepoint —
 * matching the Frida resolver (`src/resolver/resolver.ts` calls
 * `assertTargetAllowed` on every resolve path) and RFC 0001 Decision 2. The
 * `:xposed` layer keeps its defense-in-depth loader check (boot/system-loaded
 * targets) on top of this namespace guard.
 *
 * The Frida-side twin (`src/resolver/target-policy.ts`) mirrors these exact
 * semantics (same decision order, same [DEFAULT_DENY_PREFIXES]); keep the two
 * in lockstep.
 */
package io.github.xiddoc.rosetta.core.policy

import io.github.xiddoc.rosetta.core.TargetPolicyException

/**
 * Reserved top-level package prefixes a target may NOT resolve into (matched
 * on a dot boundary — `java.` denies `java.lang.Runtime` but not a
 * hypothetical `javafoo.Bar`). These are runtime/framework namespaces a map
 * has no legitimate reason to point an obfuscated app class at.
 *
 * The Frida-side twin MUST mirror this list value-for-value.
 */
public val DEFAULT_DENY_PREFIXES: List<String> =
    listOf(
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "com.sun.",
        "dalvik.",
        "android.",
        "androidx.",
        "com.android.",
        "kotlin.",
        "kotlinx.",
        "dagger.",
        "com.google.android.",
        "libcore.",
        "org.apache.harmony.",
    )

/** Default number of leading app-package labels that form the app prefix. */
public const val DEFAULT_APP_NAMESPACE_LABELS: Int = 2

/**
 * Policy for the target namespace guard.
 *
 * @property denyPrefixes Caller-supplied reserved prefixes. When
 *   [mergeDenylist] is true (default) these are added to
 *   [DEFAULT_DENY_PREFIXES]; when false they REPLACE the defaults entirely
 *   (use with care — that opens framework namespaces).
 * @property mergeDenylist Whether [denyPrefixes] augment (true) or replace
 *   (false) [DEFAULT_DENY_PREFIXES].
 * @property allow Exact-FQN escape hatch. A target whose FQN matches an entry
 *   here is ALLOWED even if it lands on a reserved prefix — for the rare
 *   legitimate framework hook. Exact, case-sensitive match on the normalized
 *   (array/`L...;` stripped) element FQN.
 * @property appNamespaceLabels How many leading dot-separated labels of the
 *   map's `app` form the app's own namespace prefix (default 2, e.g.
 *   `com.example` from `com.example.app`).
 */
public data class TargetPolicy(
    val denyPrefixes: List<String> = DEFAULT_DENY_PREFIXES,
    val mergeDenylist: Boolean = true,
    val allow: List<String> = emptyList(),
    val appNamespaceLabels: Int = DEFAULT_APP_NAMESPACE_LABELS,
) {
    /**
     * The effective denylist after applying [mergeDenylist].
     *
     * Public (read-only, derived) so a caller can audit the resolved denylist;
     * it carries no mutable state.
     */
    public val effectiveDenyPrefixes: List<String> =
        if (mergeDenylist) DEFAULT_DENY_PREFIXES + denyPrefixes else denyPrefixes
}

/**
 * Pure decision core for the target namespace guard. Stateless and free of any
 * reflection so it is unit-testable in isolation; the `:core` [Resolver] and
 * the `:xposed` binding are the runtime callers.
 */
public object TargetGuard {
    /** A normalization outcome: either a loadable element FQN, or "always allow". */
    private sealed interface Normalized {
        /** Primitive / void / empty — not a loadable class, never a threat. */
        object AlwaysAllow : Normalized

        /** The element class FQN to apply namespace rules to. */
        data class Element(
            val fqn: String,
        ) : Normalized
    }

    /**
     * Derive the app namespace prefix (the first [TargetPolicy.appNamespaceLabels]
     * dot-separated labels of [app]). Used by the binding to pass a concrete
     * prefix into [isAllowed]/[assertAllowed].
     *
     * **Caveat:** a very short [app] value (e.g. a single-label package like
     * `"myapp"`) yields an equally short — and therefore very broad — app prefix.
     * Callers should validate that `app` is a well-formed reverse-DNS package name
     * before calling this function.
     */
    public fun appPrefixOf(
        app: String,
        policy: TargetPolicy,
    ): String {
        if (policy.appNamespaceLabels <= 0) return ""
        val labels = app.split('.')
        return labels.take(policy.appNamespaceLabels).joinToString(".")
    }

    /**
     * Decide whether [fqn] is an allowed resolution target for an app whose
     * namespace prefix is [appPrefix], under [policy]. Pure; see the file
     * header for the decision order.
     */
    public fun isAllowed(
        fqn: String,
        appPrefix: String,
        policy: TargetPolicy,
    ): Boolean = decide(fqn, appPrefix, policy) == null

    /**
     * Assert that [fqn] (produced for real name [name]) is an allowed target,
     * or throw [TargetPolicyException]. The throw happens before any class
     * load, so a forbidden target never reaches `Class.forName` /
     * `setAccessible`.
     */
    public fun assertAllowed(
        name: String,
        fqn: String,
        appPrefix: String,
        policy: TargetPolicy,
    ) {
        val reason = decide(fqn, appPrefix, policy) ?: return
        throw TargetPolicyException(name = name, target = fqn, reason = reason)
    }

    /**
     * The single decision point. Returns `null` when allowed, or a human reason
     * when denied. Decision order (fail-closed):
     *
     *  1. primitive / void / empty after normalization → ALLOW (not loadable);
     *  2. exact-FQN allowlist → ALLOW;
     *  3. top-level prefix on the reserved denylist → DENY (even if it also
     *     matches the app prefix);
     *  4. package-local (no `.`) → ALLOW;
     *  5. starts with the app's own prefix (dot boundary) → ALLOW;
     *  6. else → DENY.
     */
    private fun decide(
        fqn: String,
        appPrefix: String,
        policy: TargetPolicy,
    ): String? {
        val element =
            when (val n = normalize(fqn)) {
                is Normalized.AlwaysAllow -> return null
                is Normalized.Element -> n.fqn
            }

        // (2) explicit escape hatch — exact, case-sensitive FQN match.
        if (policy.allow.contains(element)) return null

        // The namespace is everything before the first nested-class `$`.
        val namespace = element.substringBefore('$')

        // (3) reserved denylist (dot-boundary), highest priority after allow.
        val denied = policy.effectiveDenyPrefixes.firstOrNull { matchesPrefix(namespace, it) }
        if (denied != null) {
            return "namespace '$namespace' is on the reserved denylist (prefix '$denied')"
        }

        // (4) package-local: no dot in the namespace at all.
        if (!namespace.contains('.')) return null

        // (5) app's own prefix, on a dot boundary.
        if (appPrefix.isNotEmpty() && matchesPrefix(namespace, "$appPrefix.")) return null

        // (6) everything else is foreign — deny.
        return "namespace '$namespace' is neither package-local nor within the app prefix " +
            "'${appPrefix.ifEmpty { "<none>" }}'"
    }

    /**
     * Strip array markers down to the element class FQN. Handles both the
     * reflective form (`[[Lcom.example.Foo;`) and a source-ish form
     * (`com.example.Foo[]`). Primitives/void (single-letter descriptors or the
     * `void`/primitive keywords) and the empty string are [Normalized.AlwaysAllow]
     * — they are never loadable classes a hook could be redirected at.
     */
    private fun normalize(fqn: String): Normalized {
        var s = fqn.trim()
        // `com.example.Foo[]` → `com.example.Foo`
        while (s.endsWith("[]")) {
            s = s.substring(0, s.length - 2).trim()
        }
        // Leading `[` array depth markers (reflective array class names).
        var i = 0
        while (i < s.length && s[i] == '[') i++
        val body = s.substring(i)
        val hadArray = i > 0

        if (body.isEmpty()) return Normalized.AlwaysAllow

        // Reflective object-array element: `Lcom.example.Foo;` (or with `/`).
        val isObjectArrayElement = hadArray && body.length >= 2 && body.startsWith("L") && body.endsWith(";")
        if (isObjectArrayElement) {
            val inner = body.substring(1, body.length - 1).replace('/', '.')
            return if (inner.isEmpty()) Normalized.AlwaysAllow else Normalized.Element(inner)
        }

        // After stripping array markers, a single-char body is a primitive
        // descriptor (Z B C S I J F D) or void (V) — not a loadable class.
        if (hadArray && body.length == 1) return Normalized.AlwaysAllow

        // Bare primitive / void keywords (source form).
        if (body in PRIMITIVE_KEYWORDS) return Normalized.AlwaysAllow

        return Normalized.Element(body.replace('/', '.'))
    }

    /** True if [namespace] equals [prefix] (sans trailing dot) or sits under it on a dot boundary. */
    private fun matchesPrefix(
        namespace: String,
        prefix: String,
    ): Boolean {
        if (namespace.startsWith(prefix)) return true
        // Allow `prefix` like `java.` to also match a bare `java` namespace
        // (defensive — real FQNs always have the trailing segment).
        val bare = prefix.removeSuffix(".")
        return namespace == bare
    }

    private val PRIMITIVE_KEYWORDS =
        setOf("void", "boolean", "byte", "char", "short", "int", "long", "float", "double")
}
