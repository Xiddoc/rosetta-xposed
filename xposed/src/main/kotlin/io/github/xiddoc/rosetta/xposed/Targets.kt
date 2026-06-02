/*
 * Bind targets — the bridge between a neutral resolution result and a
 * concrete, hookable reflection [Member] on the running app's classes.
 *
 * A target is produced by [RosettaXposed] from a resolved real name. It
 * loads the OBFUSCATED class through the supplied class loader and selects
 * the member whose name and parameter descriptors match the resolved map
 * signature. The developer then hands the member to their hook framework
 * via [Hooker].
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.resolver.ResolvedField
import io.github.xiddoc.rosetta.core.resolver.ResolvedMethod
import io.github.xiddoc.rosetta.core.resolver.parseSignatureArgs
import java.lang.reflect.Field
import java.lang.reflect.Member

/** A class could not be loaded, or a resolved member was not found on it. */
public class BindException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** A resolved class plus the loader used to realise it. */
public class ClassTarget internal constructor(
    public val realName: String,
    public val obfName: String,
    private val classLoader: ClassLoader,
) {
    /** Load the obfuscated [Class], or throw [BindException] if not yet present. */
    public fun load(): Class<*> =
        try {
            Class.forName(obfName, false, classLoader)
        } catch (ex: ClassNotFoundException) {
            throw BindException(
                "rosetta-xposed: obfuscated class '$obfName' (real '$realName') is not loadable " +
                    "yet. For packed/late-loaded dex, bind via DeferredBinding once the class " +
                    "appears.",
                ex,
            )
        }
}

/** A resolved method/constructor, bindable to a concrete [Member]. */
public class MethodTarget internal constructor(
    public val resolved: ResolvedMethod,
    private val classLoader: ClassLoader,
) {
    /** Find the concrete [Member] (Method or Constructor) on the loaded class. */
    public fun member(): Member {
        val cls = Class.forName(resolved.className, false, classLoader)
        val wantArgs = parseSignatureArgs(resolved.signature)

        if (resolved.obfName == "<init>") {
            return cls.declaredConstructors.firstOrNull {
                JvmDescriptors.paramsOf(it.parameterTypes) == wantArgs
            }?.also { it.isAccessible = true }
                ?: throw BindException(
                    "rosetta-xposed: no constructor of '${resolved.className}' matches " +
                        "${resolved.signature}.",
                )
        }

        return cls.declaredMethods.firstOrNull {
            it.name == resolved.obfName && JvmDescriptors.paramsOf(it.parameterTypes) == wantArgs
        }?.also { it.isAccessible = true }
            ?: throw BindException(
                "rosetta-xposed: no method '${resolved.obfName}${resolved.signature}' on " +
                    "'${resolved.className}'.",
            )
    }

    /** Resolve the member and hand it to [hooker] to apply the hook. */
    public fun hook(hooker: Hooker): Unhook? = hooker.hook(member())
}

/** A resolved field, bindable to a concrete reflection [Field]. */
public class FieldTarget internal constructor(
    public val resolved: ResolvedField,
    private val classLoader: ClassLoader,
) {
    /** Find the concrete [Field] on the loaded class. */
    public fun field(): Field {
        val cls = Class.forName(resolved.className, false, classLoader)
        return cls.declaredFields.firstOrNull { it.name == resolved.obfName }
            ?.also { it.isAccessible = true }
            ?: throw BindException(
                "rosetta-xposed: no field '${resolved.obfName}' on '${resolved.className}'.",
            )
    }
}
