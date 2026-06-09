/*
 * Resolution results — Kotlin twin of rosetta-frida's
 * `src/types/resolver.ts`.
 *
 * These carry the obfuscated names (plus enough metadata to bind a hook)
 * that a layer-4 adapter needs. The neutral core produces them; it never
 * touches a real runtime.
 */
package io.github.xiddoc.rosetta.core.resolver

import io.github.xiddoc.rosetta.core.model.FieldEntry
import io.github.xiddoc.rosetta.core.model.MethodEntry
import io.github.xiddoc.rosetta.core.model.Methods

/**
 * Result of resolving a class — the resolved coordinates a layer-4 binding
 * needs, NOT the whole map [io.github.xiddoc.rosetta.core.model.ClassEntry].
 *
 * The full entry stays private to the [Resolver]; only the fields a consumer
 * actually reads are surfaced here, so the *read side* of the resolution
 * boundary carries resolved coordinates rather than the map model. (The
 * write-back is a separate, deliberately typed contract — the
 * [DiscoveredClass] below — and it DOES reference the model method/field value
 * types it has to rebuild into a [io.github.xiddoc.rosetta.core.model.ClassEntry];
 * see [Resolver.override]. The boundary is model-free on the way out, not on
 * the way back in.)
 */
public data class ResolvedClass(
    /** Real fully-qualified name. */
    val realName: String,
    /** Obfuscated short name. */
    val obfName: String,
    /**
     * Parent class (real or obfuscated name), if the entry declared one.
     *
     * NOT load-bearing on the resolve→bind path. The `:xposed` inherited-member
     * walk ([io.github.xiddoc.rosetta.core.resolver] consumers in
     * `Targets.MethodTarget.member` / `FieldTarget.field`) traverses the RUNTIME
     * superclass chain (`Class.superclass`), not this field — so it is robust
     * even when a map omits the `extends` edge, and is carried THROUGH
     * UNTRANSLATED (the Frida twin does the same; translating it would diverge
     * the conformance fixture). Treat it as introspection metadata only; do not
     * assume the bind path reads it.
     */
    val extends: String? = null,
)

/**
 * The typed write-back contract for a runtime-discovered class — what a
 * dynamic / self-healing backend feeds into [Resolver.override] so the next
 * lookup of [realName] is an O(1) static hit.
 *
 * It carries exactly the fields the resolver needs to re-resolve the class,
 * its methods, and its fields — and nothing else. Provenance (kind / source /
 * anchors) is the discovery sink's concern, not the resolver's, so it is
 * intentionally absent here.
 *
 * NOTE: unlike [ResolvedClass] (the read side), this write-back contract DOES
 * reference the model value types [Methods] and [FieldEntry] — it has to, to
 * rebuild a [io.github.xiddoc.rosetta.core.model.ClassEntry] in
 * [Resolver.override]. The resolver boundary is therefore model-free outbound
 * (read) but not inbound (write-back); that is by design for a skeleton.
 */
public data class DiscoveredClass(
    /** Real fully-qualified name being healed into the static path. */
    val realName: String,
    /** Discovered obfuscated short name. */
    val obfName: String,
    /** Parent class (real or obfuscated name), if known. */
    val extends: String? = null,
    /** Discovered methods keyed by real name (one-or-more overloads each). */
    val methods: Methods? = null,
    /** Discovered fields keyed by real name. */
    val fields: Map<String, FieldEntry>? = null,
)

/**
 * Result of resolving a method on a class.
 *
 * The tri-state flags ([static] / [synthetic] / [isConstructor]) are kept as
 * `Boolean?` to preserve the asserted-vs-unknown distinction the map carries:
 * `null` means "the map did not state it" (NOT "false"). A consumer that wants
 * the old fold can read `static == true`.
 */
public data class ResolvedMethod(
    /** Real method name. */
    val realName: String,
    /** Obfuscated method name. */
    val obfName: String,
    /** The obfuscated short class name this method lives on. */
    val className: String,
    /** Method signature in JVM descriptor form (obfuscated class refs). */
    val signature: String,
    /** Optional AIDL transaction code. */
    val aidlTxn: Int?,
    /** Static flag, or `null` when the map did not assert it. */
    val static: Boolean?,
    /** Synthetic (compiler-generated) flag, or `null` when not asserted. */
    val synthetic: Boolean? = null,
    /** Constructor flag, or `null` when the map did not assert it. */
    val isConstructor: Boolean? = null,
    /** All overloads when the real name had several — the selected one is at [0]. */
    val allOverloads: List<MethodEntry>,
)

/**
 * Result of resolving a field on a class.
 *
 * [static] is `Boolean?` for the same reason as on [ResolvedMethod]: `null`
 * means the map did not assert staticness, not that the field is non-static.
 */
public data class ResolvedField(
    /** Real field name. */
    val realName: String,
    /** Obfuscated field name. */
    val obfName: String,
    /** The obfuscated short class name this field lives on. */
    val className: String,
    /** Field type in JVM descriptor form. */
    val type: String,
    /** Static flag, or `null` when the map did not assert it. */
    val static: Boolean?,
)
