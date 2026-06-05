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
 *        val rosetta = RosettaXposed.fromMap(map, classLoader)
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

public class RosettaXposed(
    private val backend: ResolutionBackend,
    private val classLoader: ClassLoader,
) {
    /** True when the loaded map (or backend) knows [realClass]. */
    public fun knows(realClass: String): Boolean = backend.canResolve(realClass)

    /** A class target — load the obfuscated class behind a real name. */
    public fun useClass(realClass: String): ClassTarget {
        val resolved = backend.resolveClass(realClass)
        return ClassTarget(resolved.realName, resolved.obfName, classLoader)
    }

    /**
     * A method target. Pass [argTypes] (real names + framework types) to
     * pick a specific overload; omit them when the name has exactly one.
     */
    public fun method(
        realClass: String,
        realMethod: String,
        argTypes: List<String>? = null,
    ): MethodTarget = MethodTarget(backend.resolveMethod(realClass, realMethod, argTypes), classLoader)

    /** A field target. */
    public fun field(
        realClass: String,
        realField: String,
    ): FieldTarget = FieldTarget(backend.resolveField(realClass, realField), classLoader)

    public companion object {
        /**
         * Build over a single static map, **without** signer verification.
         *
         * No [AppIdentity] is available on this path, so a map's
         * `signer_sha256` guard (if any) is *not* checked — use
         * [fromMap] with an identity, or [fromRegistry], when you can supply
         * the running app's signing-certificate hash. Prefer the
         * identity-bearing overload in production modules.
         */
        public fun fromMap(
            map: RosettaMap,
            classLoader: ClassLoader,
        ): RosettaXposed = RosettaXposed(StaticResolutionBackend(map), classLoader)

        /**
         * Build over a single static map, enforcing the map's
         * `signer_sha256` authenticity guard against [identity] first
         * (fail-closed — see [verifySigner]).
         *
         * @throws io.github.xiddoc.rosetta.core.MissingSignerException if the
         *   map demands a signer hash [identity] does not carry.
         * @throws io.github.xiddoc.rosetta.core.SignerMismatchException if the
         *   hashes are present but differ.
         */
        public fun fromMap(
            map: RosettaMap,
            classLoader: ClassLoader,
            identity: AppIdentity,
        ): RosettaXposed {
            verifySigner(map, identity)
            return RosettaXposed(StaticResolutionBackend(map), classLoader)
        }

        /**
         * Enforce [map]'s optional `signer_sha256` guard against [identity]
         * (RFC 0001 Decision 4). Opt-in per map: a map with no
         * `signer_sha256` is not checked. Delegates to [SignerGuard.verify];
         * both [fromMap] (identity overload) and [fromRegistry] use it so the
         * fail-closed semantics live in one place.
         */
        public fun verifySigner(
            map: RosettaMap,
            identity: AppIdentity,
        ): Unit = SignerGuard.verify(map, identity)

        /**
         * Select a map from a registry by the running app's identity, then
         * build over it after enforcing the map's `signer_sha256` guard
         * (fail-closed — see [verifySigner]). Returns `null` if no map matches
         * the version_code (the point at which a real module would fall back
         * to the dynamic DexKit backend).
         *
         * @throws io.github.xiddoc.rosetta.core.MissingSignerException if the
         *   selected map demands a signer hash [identity] does not carry.
         * @throws io.github.xiddoc.rosetta.core.SignerMismatchException if the
         *   selected map's signer hash and [identity]'s differ.
         */
        public fun fromRegistry(
            registry: MapRegistry,
            identity: AppIdentity,
            classLoader: ClassLoader,
        ): RosettaXposed? {
            val selected =
                VersionMatch.select(registry, identity.versionCode, identity.versionName)
                    ?: return null
            return fromMap(selected.map, classLoader, identity)
        }
    }
}
