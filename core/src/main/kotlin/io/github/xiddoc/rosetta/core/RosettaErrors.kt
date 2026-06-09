/*
 * Error types for the neutral core. Mirrors the failure taxonomy of
 * rosetta-frida's `src/errors.ts` (MapValidationError, ResolveError,
 * AmbiguousOverloadError) so behaviour is comparable across the two
 * resolver implementations.
 */
package io.github.xiddoc.rosetta.core

/** Base type for every error the Rosetta core raises. */
public sealed class RosettaException(
    /**
     * The human-readable diagnostic. Overridden as NON-null (the base
     * `Throwable.message` is platform-nullable): every Rosetta error is
     * constructed with a concrete message, so callers can read it without a
     * null-handling branch.
     */
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** A structured validation issue: a JSON path plus a human message. */
public data class ValidationIssue(
    val path: String,
    val message: String,
)

/** The loaded value did not satisfy the map schema. */
public class MapValidationException(
    message: String,
    public val issues: List<ValidationIssue> = emptyList(),
    cause: Throwable? = null,
) : RosettaException(message, cause)

/**
 * The raw input handed to [MapLoader.fromJson] was rejected by a cheap
 * pre-parse denial-of-service guard, before any deserialization ran.
 *
 * Two distinct abuse shapes are caught fail-fast: an input larger than
 * [MapLoader.MAX_INPUT_BYTES] (a memory-pressure / pathological-parse
 * vector), and JSON nested deeper than [MapLoader.MAX_NESTING_DEPTH] (a
 * stack-overflow vector against kotlinx-serialization's recursive
 * descent). These caps mirror the canonical rosetta-maps JSON Schema
 * (the authoritative reference; the frida Zod and this Kotlin client
 * track it).
 */
public class MapInputTooLargeException(
    message: String,
) : RosettaException(message)

/**
 * A map declares `status: retracted` (schema 3, maps#40) and was refused at
 * load time. Fail-closed: a retracted map was withdrawn upstream (e.g. found to
 * mis-resolve), so its obfuscated names must never bind. Distinct from a
 * validation failure: the map is well-formed but deliberately withdrawn.
 */
public class RetractedMapException(
    message: String,
) : RosettaException(message)

/** Which kind of symbol a [ResolveException] failed to resolve. */
public enum class ResolveTarget {
    CLASS,
    METHOD,
    FIELD,
}

/** A real name could not be resolved to an obfuscated one. */
public open class ResolveException(
    message: String,
    public val name: String,
    public val app: String,
    public val version: String,
    public val target: ResolveTarget,
    public val classScope: String? = null,
) : RosettaException(message)

/**
 * A resolution target (the fully-qualified class name a map points at, which
 * would be handed to `Class.forName`) was rejected by the namespace guard.
 *
 * Fail-closed (RFC 0001 C1): a community map maps a real name to an arbitrary
 * obfuscated string, and that string is loaded reflectively and made
 * accessible. A malicious or buggy map could redirect a hook at a sensitive
 * framework class (e.g. `java.lang.Runtime`, `android.app.*`). The guard
 * confines targets to package-local / app-owned namespaces (plus an explicit
 * escape-hatch allowlist) and THROWS this — before any class load or
 * `setAccessible` — for anything else.
 *
 * This is distinct from the "class not present yet" case (which the layer-4
 * binding reports as its own `BindException`): a [TargetPolicyException] means
 * the target is *forbidden*, not merely *absent*.
 */
public class TargetPolicyException(
    /** The real name being resolved when the forbidden target was produced. */
    public val name: String,
    /** The rejected target FQN (what would have been passed to `Class.forName`). */
    public val target: String,
    /** Why the target was rejected (which rule denied it). */
    public val reason: String,
) : RosettaException(
        "rosetta: target '$target' for real name '$name' is forbidden by the namespace guard: $reason",
    )

/**
 * A real-name argument type passed to overload disambiguation is not a known
 * class in the map (and no overload uses its literal descriptor either), so the
 * resolver cannot translate it. This is raised IN PLACE OF the generic
 * no-overload-matches [ResolveException] so the failure points at the real
 * cause (an unmapped arg type) instead of misattributing it to the overload
 * set. It IS a [ResolveException] subtype so existing `Resolve`-error handling
 * still catches it.
 */
public class UnknownArgTypeException(
    message: String,
    name: String,
    app: String,
    version: String,
    classScope: String,
    /** The offending argument type name that is not a known map class. */
    public val argType: String,
) : ResolveException(message, name, app, version, ResolveTarget.METHOD, classScope)

/** A method name had several overloads and no arg types were supplied. */
public class AmbiguousOverloadException(
    message: String,
    public val methodName: String,
    // `classScope` (not `className`) for parity with rosetta-frida's
    // AmbiguousOverloadError in src/errors.ts — the field names match so the
    // two clients' error shapes stay diffable.
    public val classScope: String,
    public val overloadCount: Int,
) : RosettaException(message)

/**
 * A map's `signer_sha256` authenticity guard did not match the running
 * app's signing certificate hash. Fail-closed: the map is for a build
 * signed by a different (possibly repackaged/spoofed) certificate, so its
 * obfuscated names cannot be trusted against this process.
 *
 * Both hashes are normalized (lowercase hex, colons/whitespace stripped)
 * before comparison; the values stored here are the normalized forms.
 */
public class SignerMismatchException(
    message: String,
    /** The normalized signer hash the map demands. */
    public val expected: String,
    /**
     * A readable rendering of the normalized signer hash set the running
     * app actually presented (a real app may be signed by several certs).
     */
    public val actual: String,
) : RosettaException(message)

/**
 * A map declares a `signer_sha256` guard but the running app presented no
 * signing-certificate hash (the app signer set was empty), so the guard
 * cannot be evaluated. Fail-closed: a map that demands authentication must
 * not be used un-authenticated.
 */
public class MissingSignerException(
    message: String,
    /** The normalized signer hash the map demands but could not verify. */
    public val expected: String,
) : RosettaException(message)

/**
 * A self-healing binding was constructed over a map that DEMANDS a
 * `signer_sha256` without supplying an `AppIdentity` to verify it, and without
 * the explicit `allowUnverified` opt-in (xposed#14 M5). This is a
 * CONSTRUCTION-time signer-guard REFUSAL — semantically a sibling of
 * [SignerMismatchException] / [MissingSignerException], so it is a
 * [RosettaException] (the core base) and lives here with the other signer
 * errors. It is deliberately NOT an `XposedBindingFailure` (the layer-4 marker),
 * so a module's per-target hook-loop catch clause must not swallow it. Thrown
 * only by `RosettaXposed.fromMapWithDiscovery`; the fix is to pass an identity,
 * set `allowUnverified=true`, or use `RosettaXposed.fromMapUnverified`.
 */
public class UnverifiedDiscoveryException(
    message: String,
) : RosettaException(message)

/**
 * A `signer_sha256` hash is not well-formed. After normalization
 * (lowercase, with `:` separators and surrounding whitespace stripped) a
 * signer hash must be exactly 64 lowercase hex characters (a SHA-256
 * digest). This is thrown for a malformed *map* hash, which is the
 * load-bearing case: a map that demands a signer must demand a valid one.
 */
public class MalformedSignerException(
    /** The offending hash value, as supplied (before/around normalization). */
    public val value: String,
    /** Why it was rejected (e.g. "expected 64 hex chars, got 8"). */
    public val reason: String,
) : RosettaException("Malformed signer_sha256 \"$value\": $reason")
