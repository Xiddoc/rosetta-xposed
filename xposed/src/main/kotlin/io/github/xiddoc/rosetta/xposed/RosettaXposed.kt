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
        /** Build over a single static map. */
        public fun fromMap(
            map: RosettaMap,
            classLoader: ClassLoader,
        ): RosettaXposed = RosettaXposed(StaticResolutionBackend(map), classLoader)

        /**
         * Select a map from a registry by the running app's identity, then
         * build over it. Returns `null` if no map matches the version_code
         * (the point at which a real module would fall back to the dynamic
         * DexKit backend).
         */
        public fun fromRegistry(
            registry: MapRegistry,
            identity: AppIdentity,
            classLoader: ClassLoader,
        ): RosettaXposed? {
            val selected =
                VersionMatch.select(registry, identity.versionCode, identity.versionName)
                    ?: return null
            return fromMap(selected.map, classLoader)
        }
    }
}
