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

import io.github.xiddoc.rosetta.core.RetractedMapException
import io.github.xiddoc.rosetta.core.UnverifiedDiscoveryException
import io.github.xiddoc.rosetta.core.model.MapStatus
import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.signature.SignatureSet
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

        /**
         * Fail-closed construction gate (maps#40): refuse a `status: retracted`
         * [map] before any binding is built. This is the AUTHORITATIVE,
         * UNIFORM retraction gate — invoked at the TOP of every construction
         * factory below, beside (but ORTHOGONAL to) the signer guard, so a
         * retracted map can never bind through ANY path. It fires even on
         * [fromMapUnverified] (which only skips the SIGNER check), mirroring the
         * frida client: a retracted map's obfuscated names were withdrawn
         * upstream and must never bind, regardless of signer verification.
         *
         * Distinct from the SUPERSEDED warning, which stays an opt-in
         * health-check signal ([healthCheck]) and is deliberately NOT moved to
         * construction (CLAUDE.md Decision 5: the factories do not run the
         * health check).
         *
         * @throws RetractedMapException if [map] declares `status: retracted`.
         */
        private fun refuseIfRetracted(map: RosettaMap) {
            if (map.status == MapStatus.RETRACTED) {
                throw RetractedMapException(
                    "Map for ${map.app}@${map.version} (version_code=${map.versionCode}) is RETRACTED " +
                        "and must not be used; its obfuscated names were withdrawn upstream.",
                )
            }
        }

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
            refuseIfRetracted(map)
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
        ): RosettaXposed {
            // Retraction is a fail-closed gate ORTHOGONAL to the signer check:
            // it fires even here, where the signer guard is deliberately skipped
            // (mirroring frida — a retracted map is refused even with the signer
            // guard disabled).
            refuseIfRetracted(map)
            return RosettaXposed(StaticResolutionBackend(map, policy), classLoader, map.app, policy)
        }

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
            guardConstruction(map, identity, allowUnverified)
            return buildWithDiscovery(map, index, classLoader, discovery, policy)
        }

        /**
         * The construction gate shared by the discovery factories: refuse a
         * retracted map, then enforce the signer guard (or the explicit
         * unverified opt-in). Runs BEFORE any work — so a retracted /
         * signer-mismatched map is refused before, and instead of, signature
         * compilation (which would otherwise misattribute the failure).
         */
        private fun guardConstruction(
            map: RosettaMap,
            identity: AppIdentity?,
            allowUnverified: Boolean,
        ) {
            refuseIfRetracted(map)
            if (identity != null) {
                SignerGuard.verify(map, identity)
            } else if (!map.signerSha256s.isNullOrEmpty() && !allowUnverified) {
                // The map demands authentication but the caller supplied no
                // identity and did not opt into the unverified path: fail closed
                // rather than silently skip the guard (xposed#14 M5).
                throw UnverifiedDiscoveryException(
                    "rosetta-xposed: map for ${map.app}@${map.version} (version_code=${map.versionCode}) " +
                        "carries a signer_sha256 but a discovery binding was constructed without an AppIdentity. " +
                        "Pass an identity to verify it, or set allowUnverified=true to deliberately skip the " +
                        "signer guard (or use fromMapUnverified).",
                )
            }
        }

        /**
         * Assemble the static-first composite over a discovery config. Assumes
         * [guardConstruction] has already run; it does NOT re-gate, so the two
         * discovery factories each gate exactly once.
         */
        private fun buildWithDiscovery(
            map: RosettaMap,
            index: DexKitIndex,
            classLoader: ClassLoader,
            discovery: DiscoveryConfig,
            policy: TargetPolicy,
        ): RosettaXposed {
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

        /**
         * Build a self-healing binding whose discovery is driven by Rosetta's
         * COMMUNITY SIGNATURES — the bridge that lets a module detect
         * obfuscation for a version that has **no published map yet** (RFC 0001
         * Decision 5).
         *
         * It [compiles][SignatureCompiler.compile] [signatures] (the typed form
         * of a `signatures/<app>/signatures.yaml`, loaded by
         * `core/SignatureLoader`) into the on-device DexKit discovery hints,
         * then delegates to [fromMapWithDiscovery] — so it inherits that path's
         * ENTIRE posture unchanged: retraction refusal, the signer guard
         * (enforced when [identity] != null; [allowUnverified] for a signed,
         * identity-less map), the static map's real→obf arg translator, the
         * discovery cache / sink / observer, and the C1 namespace guard over
         * every discovered FQN. See the posture matrix on this companion.
         *
         * Any hints in [discovery]`.hints` are MERGED on top of the compiled
         * signature hints, at WHOLE-CLASS granularity: an explicit hint for a
         * real name REPLACES the compiled hint for that name entirely (it is not
         * field-merged), so a hand hint that supplies only `methods` drops the
         * compiled `anchors` for that class. Use it to hand-tune (or fully
         * override) a single class while the community signatures drive the
         * rest. A class whose signatures yield no usable class-locating anchor
         * is omitted by the compiler and simply stays unresolvable (fail-closed)
         * unless the static map or an explicit hint covers it — call
         * [SignatureCompiler.report] to see exactly what was dropped and why.
         *
         * The signer guard / retraction gate runs BEFORE signature compilation,
         * so a retracted or signer-mismatched map is refused with its own error
         * rather than a (possibly later) signature-compilation failure.
         *
         * @param map the (possibly empty / older) static map; known names still
         *   resolve statically, signatures cover the rest.
         * @param index the device-side discovery seam (faked in tests).
         * @param signatures the community signatures to harvest into hints.
         * @param classLoader the app class loader targets are realised through.
         * @param identity when non-null, enforces the map's signer guard before
         *   wiring discovery; see [fromMapWithDiscovery] / [allowUnverified].
         * @param discovery the provenance sink / cache / observer plus any
         *   hand-authored hints to merge over the compiled ones.
         * @param policy the C1 target namespace policy applied to every target.
         * @param allowUnverified opt-in to the unverified path for a signed,
         *   identity-less map.
         * @throws io.github.xiddoc.rosetta.core.SignatureValidationException if a
         *   harvested anchor is over the [SafePattern] bounds or is not a valid
         *   RE2 expression (a malformed signature, raised AFTER the guards pass).
         * @throws UnverifiedDiscoveryException under the same conditions as
         *   [fromMapWithDiscovery].
         */
        public fun fromMapWithSignatures(
            map: RosettaMap,
            index: DexKitIndex,
            signatures: SignatureSet,
            classLoader: ClassLoader,
            identity: AppIdentity? = null,
            discovery: DiscoveryConfig = DiscoveryConfig(),
            policy: TargetPolicy = TargetPolicy(),
            allowUnverified: Boolean = false,
        ): RosettaXposed {
            // Gate FIRST (retraction + signer), so a refused map never pays for —
            // or is misdiagnosed by — signature compilation. Then harvest, with
            // explicit hints winning per real name, and assemble (no re-gate).
            guardConstruction(map, identity, allowUnverified)
            val mergedHints = SignatureCompiler.compile(signatures) + discovery.hints
            return buildWithDiscovery(map, index, classLoader, discovery.copy(hints = mergedHints), policy)
        }
    }
}
