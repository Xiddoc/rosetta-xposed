/*
 * Error-taxonomy marker for the layer-4 binding.
 *
 * The :xposed binding raises two RuntimeException subtypes that live OUTSIDE
 * the neutral :core sealed RosettaException hierarchy (by design — they are
 * layer-4 concerns: reflective class loading and device-side discovery):
 *
 *   - [BindException]      (Targets.kt) — a target class/member could not be
 *     realised on the running app's classes;
 *   - [DiscoveryException] (DiscoveryException.kt) — a dynamic (DexKit)
 *     discovery attempt failed, fail-closed.
 *
 * This marker lets a consuming module catch "any layer-4 binding failure" in
 * one clause without enumerating the concrete types or widening the core's
 * locked taxonomy. It is a plain interface (not a common superclass) so the two
 * exceptions keep their distinct, documented identities.
 */
package io.github.xiddoc.rosetta.xposed

/** Marker for every layer-4 binding failure ([BindException] / [DiscoveryException]). */
public interface XposedBindingFailure
