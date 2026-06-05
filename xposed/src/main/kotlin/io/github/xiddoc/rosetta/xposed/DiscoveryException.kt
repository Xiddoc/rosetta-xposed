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
) : RuntimeException(message, cause)
