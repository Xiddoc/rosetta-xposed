/*
 * RosettaXposed — the entry point for the Xposed / LSPosed / LSPatch
 * layer-4 binding.
 *
 * Lifecycle inside an Xposed module:
 *
 *   1. On package-ready, build/select the map for the running version_code
 *      and capture the app class loader:
 *
 *        val map = MapLoader.fromJson(mapJson)          // or pick from a registry
 *        val rosetta = RosettaXposed.fromMap(map, classLoader, identity)
 *
 *   2. Resolve real names and hook with your framework of choice — Rosetta
 *      resolves, you hook (RFC 0001 Decision 2):
 *
 *        rosetta.method("com.example.app.RemoteServiceClient", "requestTicket")
 *            .hook { member -> XposedBridge.hookMethod(member, myHook) }
 *
 * Map selection by version_code and the (planned) DexKit self-healing
 * fallback live behind [ResolutionBackend]; this class only binds resolved
 * names to concrete members and stays framework-agnostic.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.version.MapRegistry
import io.github.xiddoc.rosetta.core.version.VersionMatch

public class RosettaXposed internal constructor(
    private val backend: ResolutionBackend,
    private val loader: TargetLoader,
) {
    /**
     * Build over a [backend] + [classLoader], confining every resolution
     * target to the namespace allowed by [policy] for the app named by
     * [appName] (RFC 0001 C1).
     */
    public constructor(
        backend: ResolutionBackend,
        classLoader: ClassLoader,
        appName: String,
        policy: TargetPolicy = TargetPolicy(),
    ) : this(
        backend,
        TargetLoader(classLoader, TargetGuard.appPrefixOf(appName, policy), policy),
    )

    /** True when the loaded map (or backend) knows [realClass]. */
    public fun knows(realClass: String): Boolean = backend.canResolve(realClass)

    /** A class target — load the obfuscated class behind a real name. */
    public fun useClass(realClass: String): ClassTarget {
        val resolved = backend.resolveClass(realClass)
        return ClassTarget(resolved.realName, resolved.obfName, loader)
    }

    /**
     * A method target. Pass [argTypes] (real names + framework types) to
     * pick a specific overload; omit them when the name has exactly one.
     */
    public fun method(
        realClass: String,
        realMethod: String,
        argTypes: List<String>? = null,
    ): MethodTarget = MethodTarget(backend.resolveMethod(realClass, realMethod, argTypes), loader)

    /** A field target. */
    public fun field(
        realClass: String,
        realField: String,
    ): FieldTarget = FieldTarget(backend.resolveField(realClass, realField), loader)

    public companion object {
        /**
         * Build over a single static map, enforcing the map's
         * `signer_sha256` authenticity guard against [identity] first
         * (fail-closed — see [SignerGuard.verify]). This is the bare, safe,
         * recommended construction path.
         *
         * @throws io.github.xiddoc.rosetta.core.MalformedSignerException if
         *   the map's `signer_sha256` is not 64 hex chars after normalization.
         * @throws io.github.xiddoc.rosetta.core.MissingSignerException if the
         *   map demands a signer hash but [identity]'s signer set is empty.
         * @throws io.github.xiddoc.rosetta.core.SignerMismatchException if the
         *   map demands a signer hash and no member of [identity]'s set matches.
         */
        public fun fromMap(
            map: RosettaMap,
            classLoader: ClassLoader,
            identity: AppIdentity,
            policy: TargetPolicy = TargetPolicy(),
        ): RosettaXposed {
            SignerGuard.verify(map, identity)
            return RosettaXposed(StaticResolutionBackend(map), classLoader, map.app, policy)
        }

        /**
         * Build over a single static map performing **NO** signer check.
         *
         * Use this ONLY when no `PackageManager` / [AppIdentity] is available
         * to verify against; a map's `signer_sha256` guard (if any) is *not*
         * enforced on this path. Prefer the identity-bearing [fromMap] (or
         * [fromRegistry]) in production modules so a map carrying a
         * `signer_sha256` is enforced fail-closed.
         */
        public fun fromMapUnverified(
            map: RosettaMap,
            classLoader: ClassLoader,
            policy: TargetPolicy = TargetPolicy(),
        ): RosettaXposed = RosettaXposed(StaticResolutionBackend(map), classLoader, map.app, policy)

        /**
         * Select a map from a registry by the running app's identity, then
         * build over it after enforcing the map's `signer_sha256` guard
         * (fail-closed — see [SignerGuard.verify]). Returns `null` if no map
         * matches the version_code (the point at which a real module would
         * fall back to the dynamic DexKit backend).
         *
         * @throws io.github.xiddoc.rosetta.core.MalformedSignerException if
         *   the selected map's `signer_sha256` is malformed.
         * @throws io.github.xiddoc.rosetta.core.MissingSignerException if the
         *   selected map demands a signer hash but [identity]'s set is empty.
         * @throws io.github.xiddoc.rosetta.core.SignerMismatchException if the
         *   selected map demands a signer hash and no member of [identity]'s
         *   set matches.
         */
        public fun fromRegistry(
            registry: MapRegistry,
            identity: AppIdentity,
            classLoader: ClassLoader,
            policy: TargetPolicy = TargetPolicy(),
        ): RosettaXposed? {
            val selected =
                VersionMatch.select(registry, identity.versionCode, identity.versionName)
                    ?: return null
            return fromMap(selected.map, classLoader, identity, policy)
        }
    }
}
