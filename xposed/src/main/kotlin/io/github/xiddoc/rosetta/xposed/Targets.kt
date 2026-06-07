/*
 * Bind targets — the bridge between a neutral resolution result and a
 * concrete, hookable reflection [Member] on the running app's classes.
 *
 * A target is produced by [RosettaXposed] from a resolved real name. It
 * loads the OBFUSCATED class through the supplied class loader and selects
 * the member whose name and parameter descriptors match the resolved map
 * signature. The developer then hands the member to their hook framework
 * via [Hooker].
 *
 * SECURITY (RFC 0001 C1). Every `Class.forName` here is funnelled through
 * [TargetLoader.loadGuardedClass], which runs the namespace guard
 * ([TargetGuard.assertAllowed]) FIRST, then loads (with `initialize = false`),
 * then applies a defense-in-depth class-loader check. `setAccessible(true)` is
 * only ever reached for an allowed, app-loaded target. A malicious map that
 * names a forbidden target (e.g. `java.lang.Runtime`) throws
 * [io.github.xiddoc.rosetta.core.TargetPolicyException] before any load.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.TargetPolicyException
import io.github.xiddoc.rosetta.core.resolver.ResolvedField
import io.github.xiddoc.rosetta.core.resolver.ResolvedMethod
import io.github.xiddoc.rosetta.core.resolver.parseSignatureArgs
import java.lang.reflect.Field
import java.lang.reflect.Member

/** A class could not be loaded, or a resolved member was not found on it. */
public class BindException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause),
    XposedBindingFailure

/**
 * The single chokepoint through which every target FQN is realised. Runs the
 * namespace guard before loading and the loader check after, so neither a
 * forbidden FQN nor a foreign (framework-loaded) class ever reaches a hook.
 *
 * @property classLoader the app class loader targets are realised through.
 * @property appPrefix the app's own namespace prefix (see [TargetGuard.appPrefixOf]).
 * @property policy the active [TargetPolicy].
 */
internal class TargetLoader(
    val classLoader: ClassLoader,
    private val appPrefix: String,
    private val policy: TargetPolicy,
) {
    /**
     * Guard, then load (without initialising), then loader-check the target
     * [fqn] produced for real name [name].
     *
     * @throws TargetPolicyException if [fqn] is forbidden by the namespace
     *   policy, or (defense-in-depth) if the loaded class was realised by the
     *   boot / system / platform loader while not explicitly allowlisted.
     * @throws BindException if the (allowed) class is not yet loadable.
     */
    fun loadGuardedClass(
        name: String,
        fqn: String,
    ): Class<*> {
        // (1) Policy gate — throws before any load / setAccessible.
        TargetGuard.assertAllowed(name = name, fqn = fqn, appPrefix = appPrefix, policy = policy)

        val cls =
            try {
                Class.forName(fqn, false, classLoader)
            } catch (ex: ClassNotFoundException) {
                throw BindException(
                    "rosetta-xposed: obfuscated class '$fqn' (real '$name') is not loadable " +
                        "yet. For packed/late-loaded dex, bind via DeferredBinding once the class " +
                        "appears.",
                    ex,
                )
            }

        // (2) Defense-in-depth loader check. An app target must be realised by
        // the app loader (or one of its descendants/ancestors short of the
        // boot/system loaders). A boot/null/system-loaded class with a
        // non-allowlisted FQN is a foreign target — hard-deny.
        // Restricting to boot/null/system/platform scope is an explicit design
        // decision (owner-approved, RFC 0001 C1): it covers every framework
        // class on a stock Android device while leaving the app's own class
        // hierarchy (realised by child loaders) fully accessible.
        if (!policy.allow.contains(normalizedElement(fqn)) && loadedByPlatformLoader(cls)) {
            throw TargetPolicyException(
                name = name,
                target = fqn,
                reason =
                    "loaded class was realised by the boot/system class loader (foreign target); " +
                        "an app target must come from the app's class loader",
            )
        }

        return cls
    }

    /**
     * Probe whether the target [fqn] (produced for real name [name]) is
     * currently loadable, WITHOUT initialising it and WITHOUT making any member
     * accessible. Used by [DeferredBinding] to decide when a late-loaded class
     * has appeared.
     *
     * The C1 namespace guard runs FIRST (same chokepoint as
     * [loadGuardedClass]), so a forbidden target throws [TargetPolicyException]
     * before any `Class.forName` — a malicious map can never use the deferred
     * probe to load a denied/framework class (RFC 0001 C1, the M1 lesson).
     * Only an allowed target is ever probe-loaded.
     *
     * @return `true` when the (allowed) class is loadable now, `false` when it
     *   is not yet present.
     * @throws TargetPolicyException if [fqn] is forbidden by the namespace
     *   policy (thrown before any load), or if a loaded (allowed) class is a
     *   foreign platform-loaded target.
     */
    fun probeLoadable(
        name: String,
        fqn: String,
    ): Boolean =
        try {
            // Routes through the SAME guard-first chokepoint: assertAllowed runs
            // inside loadGuardedClass before any Class.forName, so a denied
            // target throws TargetPolicyException and is never probe-loaded.
            loadGuardedClass(name, fqn)
            true
        } catch (_: BindException) {
            // Allowed, but the (late-loaded) class is not present yet.
            false
        }

    /** The normalized element FQN, matching the form [TargetPolicy.allow] holds. */
    private fun normalizedElement(fqn: String): String = fqn.replace('/', '.')

    /**
     * True when [cls] was defined by the boot, system, or platform class loader.
     * A null loader is the bootstrap (boot) loader. Anything else — the app
     * loader and its descendants — is accepted. This is the unambiguous
     * foreign-target case the spec keeps as a hard-deny.
     */
    private fun loadedByPlatformLoader(cls: Class<*>): Boolean {
        val loader = cls.classLoader ?: return true // null == bootstrap loader.
        return loader in platformLoaders
    }

    /**
     * The boot/system/platform loaders a non-app target would resolve through.
     *
     * On a desktop JVM the system loader's parent IS the platform loader
     * (`getSystemClassLoader().parent === getPlatformClassLoader()`), so this set
     * is exactly {system, platform} there. We deliberately source the platform
     * loader as that parent instead of calling
     * `ClassLoader.getPlatformClassLoader()` directly: that is a Java 9+ (JPMS)
     * API that does NOT exist on Android's ART at any API level, and this binding
     * runs inside the target app on-device — calling it throws NoSuchMethodError
     * at load time, before any hook is applied. Sourcing the parent works on both
     * runtimes (on Android it is the system loader's own parent, still a non-app
     * loader); a null parent is dropped.
     */
    private val platformLoaders: Set<ClassLoader> =
        ClassLoader.getSystemClassLoader().let { system ->
            setOfNotNull(system, system.parent)
        }
}

/** A resolved class plus the loader used to realise it. */
public class ClassTarget internal constructor(
    public val realName: String,
    public val obfName: String,
    private val loader: TargetLoader,
) {
    /** Load the obfuscated [Class], or throw [BindException] if not yet present. */
    public fun load(): Class<*> = loader.loadGuardedClass(realName, obfName)
}

/** A resolved method/constructor, bindable to a concrete [Member]. */
public class MethodTarget internal constructor(
    public val resolved: ResolvedMethod,
    private val loader: TargetLoader,
) {
    /**
     * Find the concrete [Member] (Method or Constructor) on the loaded class.
     *
     * INHERITED MEMBERS (xposed#12). The lookup walks the declared methods of
     * the loaded class AND its superclasses (stopping at — and excluding —
     * `java.lang.Object`), so a method declared on a PARENT class (e.g.
     * `RemoteServiceClient extends AbstractServiceClient`, where the obfuscated
     * method lives on the parent) binds successfully. `declaredMethods` alone
     * excludes inherited members; `getMethod(...)` would only see public ones,
     * so the walk + `declaredMethods` is what reaches a non-public inherited
     * method. Constructors are NOT inherited, so the constructor branch stays on
     * the declared class only.
     */
    public fun member(): Member {
        val cls = loader.loadGuardedClass(resolved.realName, resolved.className)
        val wantArgs = parseSignatureArgs(resolved.signature)

        if (resolved.obfName == "<init>") {
            return cls.declaredConstructors
                .firstOrNull {
                    JvmDescriptors.paramsOf(it.parameterTypes) == wantArgs
                }?.also { it.isAccessible = true }
                ?: throw BindException(
                    "rosetta-xposed: no constructor of '${resolved.className}' matches " +
                        "${resolved.signature}.",
                )
        }

        return findInHierarchy(cls) { c ->
            c.declaredMethods.firstOrNull {
                it.name == resolved.obfName && JvmDescriptors.paramsOf(it.parameterTypes) == wantArgs
            }
        }?.also { it.isAccessible = true }
            ?: throw BindException(
                "rosetta-xposed: no method '${resolved.obfName}${resolved.signature}' on " +
                    "'${resolved.className}' or any superclass (excluding java.lang.Object).",
            )
    }

    /** Resolve the member and hand it to [hooker] to apply the hook. */
    public fun hook(hooker: Hooker): Unhook? = hooker.hook(member())
}

/** A resolved field, bindable to a concrete reflection [Field]. */
public class FieldTarget internal constructor(
    public val resolved: ResolvedField,
    private val loader: TargetLoader,
) {
    /**
     * Find the concrete [Field] on the loaded class.
     *
     * INHERITED FIELDS (xposed#12). Like [MethodTarget.member], the lookup walks
     * the declared fields of the loaded class AND its superclasses (stopping at
     * — and excluding — `java.lang.Object`), so a field declared on a PARENT
     * class binds. `declaredFields` alone excludes inherited fields.
     */
    public fun field(): Field {
        val cls = loader.loadGuardedClass(resolved.realName, resolved.className)
        return findInHierarchy(cls) { c -> c.declaredFields.firstOrNull { it.name == resolved.obfName } }
            ?.also { it.isAccessible = true }
            ?: throw BindException(
                "rosetta-xposed: no field '${resolved.obfName}' on '${resolved.className}' " +
                    "or any superclass (excluding java.lang.Object).",
            )
    }
}

/**
 * Walk [start] and its superclass chain, stopping at (and excluding)
 * `java.lang.Object`, returning the first non-null [select] result (the member
 * found on the nearest class that declares it) or `null` if none does.
 *
 * The walk EXCLUDES `java.lang.Object` deliberately: a map should never point a
 * hook at `Object`'s members (`hashCode`/`equals`/…), and stopping short of it
 * keeps the C1 spirit — only the app's own class hierarchy is searched. The
 * obfuscated classes in the chain were already realised through the C1-guarded
 * [TargetLoader] for [start]; their superclasses are part of the same app type
 * graph (the binding never makes a member of a denied class accessible because
 * the only `setAccessible` is on the member this returns, which lives on a class
 * reachable from the guarded [start]).
 */
private inline fun <T : Member> findInHierarchy(
    start: Class<*>,
    select: (Class<*>) -> T?,
): T? {
    // generateSequence stops at the first null superclass; takeWhile excludes
    // java.lang.Object. firstNotNullOfOrNull walks nearest-class-first and
    // returns the first member a class in the chain declares.
    return generateSequence(start) { it.superclass }
        .takeWhile { it != Any::class.java }
        .firstNotNullOfOrNull(select)
}
