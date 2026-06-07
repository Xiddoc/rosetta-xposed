/*
 * DiscoveryException — the :xposed-local failure type for the dynamic
 * (self-healing) backend (B.1).
 *
 * Placement mirrors [BindException] (declared in Targets.kt): a plain
 * `RuntimeException` subtype that lives in the binding layer, NOT in the
 * neutral `:core` sealed `RosettaException` hierarchy. The dynamic backend is
 * a layer-4 concern (it depends on a device-side index and contributor
 * patterns), so its failures are layer-4 failures and must not widen the
 * core's locked error taxonomy.
 *
 * FAIL-CLOSED CONTRACT. Every abnormal discovery outcome surfaces as this
 * exception — never a silent skip and never a half-populated entry. The
 * cases that throw:
 *
 *   - a contributor pattern / anchor list is over its schema bound (a ReDoS /
 *     resource-exhaustion guard in [SafePattern], checked BEFORE compiling);
 *   - a strategy ran but produced nothing (a genuine miss);
 *   - a discovery was ambiguous or only partially resolved (e.g. a class was
 *     found but the requested member was not), which must not poison the
 *     cache with a partial mapping.
 */
package io.github.xiddoc.rosetta.xposed

/** A dynamic (DexKit) discovery attempt failed; fail-closed, never partial. */
public class DiscoveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause),
    XposedBindingFailure

/**
 * A self-healing binding was constructed over a map that DEMANDS a
 * `signer_sha256` without supplying an [AppIdentity] to verify it, and without
 * the explicit `allowUnverified` opt-in (xposed#14 M5). This is a
 * CONSTRUCTION-time security refusal, not a per-target binding failure, so it
 * is a plain `RuntimeException` and is deliberately NOT an
 * [XposedBindingFailure] (a module's hook-loop catch clause should not swallow
 * it). Thrown only by [RosettaXposed.fromMapWithDiscovery]; the fix is to pass
 * an identity, set `allowUnverified=true`, or use
 * [RosettaXposed.fromMapUnverified].
 */
public class UnverifiedDiscoveryException(
    message: String,
) : RuntimeException(message)
