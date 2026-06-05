/*
 * Error types for the neutral core. Mirrors the failure taxonomy of
 * rosetta-frida's `src/errors.ts` (MapValidationError, ResolveError,
 * AmbiguousOverloadError) so behaviour is comparable across the two
 * resolver implementations.
 */
package io.github.xiddoc.rosetta.core

/** Base type for every error the Rosetta core raises. */
public sealed class RosettaException(
    message: String,
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

/** A real name could not be resolved to an obfuscated one. */
public class ResolveException(
    message: String,
    public val name: String,
    public val app: String,
    public val version: String,
    /** "class" | "method" | "field". */
    public val target: String,
    public val classScope: String? = null,
) : RosettaException(message)

/** A method name had several overloads and no arg types were supplied. */
public class AmbiguousOverloadException(
    message: String,
    public val methodName: String,
    public val className: String,
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
    /** The normalized signer hash the running app actually presented. */
    public val actual: String,
) : RosettaException(message)

/**
 * A map declares a `signer_sha256` guard but the caller supplied no app
 * signer hash, so the guard cannot be evaluated. Fail-closed: a map that
 * demands authentication must not be used un-authenticated.
 */
public class MissingSignerException(
    message: String,
    /** The normalized signer hash the map demands but could not verify. */
    public val expected: String,
) : RosettaException(message)
