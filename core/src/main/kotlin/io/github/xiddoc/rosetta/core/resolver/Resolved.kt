/*
 * Resolution results — Kotlin twin of rosetta-frida's
 * `src/types/resolver.ts`.
 *
 * These carry the obfuscated names (plus enough metadata to bind a hook)
 * that a layer-4 adapter needs. The neutral core produces them; it never
 * touches a real runtime.
 */
package io.github.xiddoc.rosetta.core.resolver

import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.MethodEntry

/** Result of resolving a class. */
public data class ResolvedClass(
    /** Real fully-qualified name. */
    val realName: String,
    /** Obfuscated short name. */
    val obfName: String,
    /** Full class entry from the map, for downstream consumers. */
    val entry: ClassEntry,
)

/** Result of resolving a method on a class. */
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
    /** Static flag. */
    val static: Boolean,
    /** All overloads when the real name had several — the selected one is at [0]. */
    val allOverloads: List<MethodEntry>,
)

/** Result of resolving a field on a class. */
public data class ResolvedField(
    /** Real field name. */
    val realName: String,
    /** Obfuscated field name. */
    val obfName: String,
    /** The obfuscated short class name this field lives on. */
    val className: String,
    /** Field type in JVM descriptor form. */
    val type: String,
    /** Static flag. */
    val static: Boolean,
)
