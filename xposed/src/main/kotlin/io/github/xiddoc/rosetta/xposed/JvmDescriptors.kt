/*
 * Reflection ↔ JVM-descriptor helpers used to match a resolved map
 * signature against the obfuscated class's actually-loaded members.
 *
 * The map stores method signatures in JVM descriptor form with obfuscated
 * class refs (e.g. `(Landroid/os/Bundle;Lbbbb;)V`). At bind time we load
 * the obfuscated class and pick the declared member whose name and
 * parameter descriptors match.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.resolver.Descriptors

internal object JvmDescriptors {
    /**
     * JVM descriptor for a (possibly primitive / array) reflected type.
     *
     * The primitive table and the object-element rendering are NOT re-declared
     * here: they delegate to the shared `:core` [Descriptors] vocabulary so the
     * reflection bridge and the neutral resolver cannot fork. For a primitive
     * `Class<*>`, `type.name` is the bare primitive name (`int`, `boolean`, …),
     * which is exactly the [Descriptors.primitive] key.
     */
    fun of(type: Class<*>): String =
        when {
            type.isPrimitive -> Descriptors.primitive(type.name) ?: error("unknown primitive ${type.name}")
            type.isArray -> "[" + of(type.componentType)
            else -> Descriptors.objectDescriptor(type.name)
        }

    /** Parameter descriptors of a reflected executable, in declaration order. */
    fun paramsOf(parameterTypes: Array<Class<*>>): List<String> = parameterTypes.map { of(it) }
}
