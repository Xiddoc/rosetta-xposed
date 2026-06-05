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
