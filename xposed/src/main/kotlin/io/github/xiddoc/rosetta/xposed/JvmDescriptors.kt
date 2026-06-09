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
     * The JVM internal name every constructor is compiled to (`<init>`). Named
     * here rather than spelled as a bare literal at the bind site (xposed#32) so
     * the constructor-dispatch fallback in [MethodTarget.member] reads against a
     * documented constant. The authoritative constructor signal is the schema's
     * `is_constructor` flag ([io.github.xiddoc.rosetta.core.resolver.ResolvedMethod.isConstructor]);
     * a name equal to this constant is the belt-and-braces fallback for a map
     * that names a constructor `<init>` without setting the flag.
     */
    const val CONSTRUCTOR_NAME: String = "<init>"

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
            // Every JVM primitive name (incl. "void") is in the core table, so
            // the lookup is total here — `!!` documents that invariant without a
            // permanently-uncovered defensive branch.
            type.isPrimitive -> Descriptors.primitive(type.name)!!
            type.isArray -> "[" + of(type.componentType)
            else -> Descriptors.objectDescriptor(type.name)
        }

    /** Parameter descriptors of a reflected executable, in declaration order. */
    fun paramsOf(parameterTypes: Array<Class<*>>): List<String> = parameterTypes.map { of(it) }
}
