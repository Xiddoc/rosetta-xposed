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
