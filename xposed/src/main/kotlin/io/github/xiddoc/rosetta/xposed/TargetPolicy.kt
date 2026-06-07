/*
 * Target namespace guard (RFC 0001 C1) — :xposed re-exports.
 *
 * The pure decision core ([TargetGuard]), the [TargetPolicy] data class, and
 * the [DEFAULT_DENY_PREFIXES] list now live in `:core`
 * (`io.github.xiddoc.rosetta.core.policy`) so the guard is enforced at the
 * single `:core` resolve chokepoint (xposed#11 — a standalone `:core` consumer
 * must not be able to bypass C1). This file keeps the historical
 * `io.github.xiddoc.rosetta.xposed` spellings working as thin re-exports, so
 * the binding's defense-in-depth loader check ([TargetLoader]) and existing
 * call sites need no churn.
 *
 * The reflection-bearing realisation (`Class.forName` + the boot/system loader
 * check) stays in `:xposed` ([TargetLoader] in `Targets.kt`); only the
 * framework-neutral namespace decision moved down to `:core`.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.policy.DEFAULT_DENY_PREFIXES as CoreDefaultDenyPrefixes

/** @see io.github.xiddoc.rosetta.core.policy.TargetPolicy */
public typealias TargetPolicy = io.github.xiddoc.rosetta.core.policy.TargetPolicy

/** @see io.github.xiddoc.rosetta.core.policy.TargetGuard */
public typealias TargetGuard = io.github.xiddoc.rosetta.core.policy.TargetGuard

/**
 * Reserved top-level package prefixes a target may NOT resolve into. Re-exported
 * from `:core` (`io.github.xiddoc.rosetta.core.policy.DEFAULT_DENY_PREFIXES`),
 * the single source of truth; the Frida-side twin mirrors it value-for-value.
 */
public val DEFAULT_DENY_PREFIXES: List<String> = CoreDefaultDenyPrefixes
