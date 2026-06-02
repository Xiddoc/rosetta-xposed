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

internal object JvmDescriptors {
    /** JVM descriptor for a (possibly primitive / array) reflected type. */
    fun of(type: Class<*>): String =
        when {
            type == Void.TYPE -> "V"
            type == Boolean::class.javaPrimitiveType -> "Z"
            type == Byte::class.javaPrimitiveType -> "B"
            type == Char::class.javaPrimitiveType -> "C"
            type == Short::class.javaPrimitiveType -> "S"
            type == Int::class.javaPrimitiveType -> "I"
            type == Long::class.javaPrimitiveType -> "J"
            type == Float::class.javaPrimitiveType -> "F"
            type == Double::class.javaPrimitiveType -> "D"
            type.isArray -> "[" + of(type.componentType)
            else -> "L" + type.name.replace('.', '/') + ";"
        }

    /** Parameter descriptors of a reflected executable, in declaration order. */
    fun paramsOf(parameterTypes: Array<Class<*>>): List<String> = parameterTypes.map { of(it) }
}
