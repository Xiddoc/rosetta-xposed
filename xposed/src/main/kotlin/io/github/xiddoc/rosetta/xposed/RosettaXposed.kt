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
 * Map selection by version_code and the DexKit-backed self-healing fallback
 * live behind [ResolutionBackend]; this class only binds resolved names to
 * concrete members and stays framework-agnostic.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.UnverifiedDiscoveryException
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
     *
     * INTERNAL: this is an unverified construction path (it does NOT run the
     * signer guard). The only PUBLIC unverified entry point is the explicitly
     * named [Companion.fromMapUnverified]; production modules should use the
     * identity-bearing [Companion.fromMap] / [Companion.fromRegistry].
     */
    internal constructor(
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

    /**
     * Defer a hook until [realClass]'s declaring obfuscated class is loadable,
     * then run [onAvailable]. A thin convenience that forwards to
     * [DeferredBinding.whenClassAvailable]; the object API there is the full
     * surface (see it for the guarded-probe + run-once semantics).
     */
    public fun deferred(
        realClass: String,
        watcher: ClassAvailabilityWatcher,
        onAvailable: (RosettaXposed) -> Unit,
    ): Registration = DeferredBinding.whenClassAvailable(this, realClass, watcher, onAvailable)

    /**
     * Resolve [realClass] to its obfuscated FQN and probe whether it is
     * loadable NOW through the C1-guarded chokepoint. Internal seam for
     * [DeferredBinding] so the deferred probe shares the exact guard path used
     * by every other target (a denied target throws [TargetPolicyException]
     * before any load — the M1 lesson).
     *
     * [realClass] is resolved exactly ONCE per call: the returned obfuscated
     * name (or `null`) is reused by [DeferredBinding] to avoid a second
     * [ResolutionBackend.resolveClass] round-trip (which matters under a
     * discovery composite that re-runs DexKit on each miss).
     *
     * @return the obfuscated FQN if the class is loadable right now, or `null`
     *   if it is not yet present; throws [TargetPolicyException] if the map
     *   points [realClass] at a denied/reserved namespace (thrown before any
     *   [Class.forName] — the M1 lesson).
     */
    internal fun probeClassLoadable(realClass: String): String? {
        val resolved = backend.resolveClass(realClass)
        return if (loader.probeLoadable(resolved.realName, resolved.obfName)) resolved.obfName else null
    }

    /**
     * The obfuscated FQN [realClass] resolves to (pure data, no load attempt).
     * Used by [DeferredBinding] after an initial probe returns null (not yet
     * loadable) to obtain the obfName for the watcher registration without
     * a third [ResolutionBackend.resolveClass] round-trip on every signal.
     */
    internal fun resolveObfName(realClass: String): String = backend.resolveClass(realClass).obfName

    public companion object {
        /**
         * Opt-in attach-time health check (xposed#14 M8): verify [map] against
         * the running app's [identity] BEFORE the first hook and return a
         * structured [HealthCheckReport] (right app, right version_code, signer
         * guard, map sanity). This never throws — the caller inspects
         * [HealthCheckReport.ok] / [HealthCheckReport.hardFailures] /
         * [HealthCheckReport.warnings] and decides whether to proceed.
         *
         * It is a thin forward to [HealthCheck.run]; it is deliberately NOT run
         * implicitly by the construction factories (those already enforce the
         * signer guard fail-closed), so a module pays for the extra sanity pass
         * only when it asks for one.
         */
        public fun healthCheck(
            map: RosettaMap,
            identity: AppIdentity,
        ): HealthCheckReport = HealthCheck.run(map, identity)

        /*
         * SECURITY-POSTURE MATRIX for the four construction factories
         * (xposed#14 M5). Each names a different point on the
         * authenticity-vs-availability trade-off; pick by what you can supply:
         *
         * | factory               | signer guard                                                                                   | use when |
         * |-----------------------|------------------------------------------------------------------------------------------------|----------|
         * | fromMap               | ENFORCED fail-closed (identity required)                                                       | you have an AppIdentity and a (maybe) signed map — the default, recommended path |
         * | fromRegistry          | ENFORCED fail-closed (identity required)                                                       | you select by version_code from a registry and have an AppIdentity |
         * | fromMapWithDiscovery  | ENFORCED when identity != null; a signed map with NO identity needs explicit allowUnverified   | self-healing discovery; the unverified path is opt-in, not silent |
         * | fromMapUnverified     | NOT checked (no identity)                                                                      | no PackageManager/AppIdentity is reachable at all — the explicitly-named escape hatch |
         *
         * The single rule across all four: a map's `signer_sha256` is NEVER
         * silently skipped. It is either verified, or the caller has explicitly
         * named an unverified path (fromMapUnverified) or opted into one
         * (fromMapWithDiscovery's allowUnverified).
         */

        /**
         * Build over a single static map, enforcing the map's
         * `signer_sha256` authenticity guard against [identity] first
         * (fail-closed — see [SignerGuard.verify]). This is the bare, safe,
         * recommended construction path.
         *
         * SECURITY POSTURE: signer guard ENFORCED fail-closed. See the
         * posture matrix on this companion.
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
            return RosettaXposed(StaticResolutionBackend(map, policy), classLoader, map.app, policy)
        }

        /**
         * Build over a single static map performing **NO** signer check.
         *
         * SECURITY POSTURE: signer guard NOT checked — the explicitly-named
         * escape hatch in the posture matrix on this companion.
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
        ): RosettaXposed = RosettaXposed(StaticResolutionBackend(map, policy), classLoader, map.app, policy)

        /**
         * Select a map from a registry by the running app's identity, then
         * build over it after enforcing the map's `signer_sha256` guard
         * (fail-closed — see [SignerGuard.verify]). Returns `null` if no map
         * matches the version_code (the point at which a real module would
         * fall back to the dynamic DexKit backend).
         *
         * SECURITY POSTURE: signer guard ENFORCED fail-closed. See the
         * posture matrix on this companion.
         *
         * @param allowFuzzyMatch opt-in fuzzy `versionName` fallback (RFC 0001
         *   Decision 3): when `true` and no exact version_code / label match is
         *   found, the closest registered label is selected. Off by default so a
         *   missing exact map fails loudly rather than silently binding a
         *   wrong-version map. Forwarded to [VersionMatch.select].
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
            allowFuzzyMatch: Boolean = false,
        ): RosettaXposed? {
            val selected =
                VersionMatch.select(
                    registry,
                    identity.versionCode,
                    identity.versionName,
                    allowFuzzyMatch,
                ) ?: return null
            return fromMap(selected.map, classLoader, identity, policy)
        }

        /**
         * Build a self-healing binding over [map] with a dynamic discovery
         * fallback ([CompositeResolutionBackend]): static-first, and on a
         * static miss it discovers the obfuscated names live via [index]
         * (writing each discovery back so the next lookup is O(1) static).
         *
         * SECURITY POSTURE: signer guard ENFORCED when [identity] != null. A
         * map that CARRIES a `signer_sha256` but is built with NO [identity]
         * requires [allowUnverified] = true, otherwise this throws
         * [UnverifiedDiscoveryException] — the fail-open path is now an EXPLICIT
         * opt-in, not a silent skip (xposed#14 M5). See the posture matrix on
         * this companion.
         *
         * Discovery runs AFTER the signer guard, and discovered names are
         * guarded by C1. When [identity] is supplied the map's `signer_sha256`
         * guard is enforced fail-closed FIRST (so only a trusted process ever
         * reaches discovery). Either way, a discovered obfuscated FQN is NOT
         * trusted blindly: every target — discovered or static — is realised
         * through the SAME [TargetLoader.loadGuardedClass] chokepoint, so the
         * C1 namespace guard rejects a discovery result that lands on a
         * reserved namespace (e.g. `java.lang.Runtime`) before any class load.
         *
         * @param map the (possibly empty / incomplete) static map.
         * @param index the device-side discovery seam (faked in tests).
         * @param classLoader the app class loader targets are realised through.
         * @param identity when non-null, enforces the map's signer guard before
         *   wiring discovery; when null, the signer check is skipped (see
         *   [allowUnverified] for the signed-map case).
         * @param discovery the contributor discovery recipes + provenance sink.
         * @param policy the C1 target namespace policy applied to every target
         *   (discovered or static).
         * @param allowUnverified opt-in to the unverified path: only consulted
         *   when [identity] is null AND [map] carries a `signer_sha256`. It is a
         *   NO-OP when [identity] != null — an identity always enforces the guard
         *   fail-closed, so the flag cannot loosen a verified construction. Leave
         *   `false` (the default) and a signed, identity-less map fails closed
         *   with [UnverifiedDiscoveryException]; set `true` to deliberately accept
         *   an authenticated map without checking it (e.g. early bring-up before
         *   an `AppIdentity` is wired). An unsigned map needs no opt-in.
         * @throws UnverifiedDiscoveryException if [identity] is null, [map]
         *   demands a signer, and [allowUnverified] is false.
         */
        public fun fromMapWithDiscovery(
            map: RosettaMap,
            index: DexKitIndex,
            classLoader: ClassLoader,
            identity: AppIdentity? = null,
            discovery: DiscoveryConfig = DiscoveryConfig(),
            policy: TargetPolicy = TargetPolicy(),
            allowUnverified: Boolean = false,
        ): RosettaXposed {
            if (identity != null) {
                SignerGuard.verify(map, identity)
            } else if (!map.signerSha256s.isNullOrEmpty() && !allowUnverified) {
                // The map demands authentication but the caller supplied no
                // identity and did not opt into the unverified path: fail closed
                // rather than silently skip the guard (xposed#14 M5).
                throw UnverifiedDiscoveryException(
                    "rosetta-xposed: map for ${map.app}@${map.version} (version_code=${map.versionCode}) " +
                        "carries a signer_sha256 but fromMapWithDiscovery was called without an AppIdentity. " +
                        "Pass an identity to verify it, or set allowUnverified=true to deliberately skip the " +
                        "signer guard (or use fromMapUnverified).",
                )
            }
            val static = StaticResolutionBackend(map, policy)
            val composite =
                CompositeResolutionBackend(
                    static = static,
                    // Translate the dynamic backend's `argTypes` through the SAME
                    // map the static resolver uses (real → obf), so a mapped
                    // app-class arg type matches a discovered overload's obf
                    // descriptor instead of spuriously failing on identity. The
                    // discovery cache (rosetta-xposed#19) is threaded through so a
                    // discovery survives a process restart.
                    dynamic =
                        DynamicResolutionBackend(
                            index,
                            discovery.hints,
                            discovery.sink,
                            static::translateType,
                            discovery.cache,
                            discovery.observer,
                        ),
                )
            return RosettaXposed(composite, classLoader, map.app, policy)
        }
    }
}
