/*
 * Method-signature utilities — Kotlin twin of rosetta-frida's
 * `src/resolver/signature.ts`.
 *
 * Two related conversions:
 *   - A JVM method descriptor `(Landroid/os/Bundle;Lbbbb;)V` splits into a
 *     list of argument descriptors.
 *   - A user-supplied type name (real-name or framework type) becomes a JVM
 *     descriptor, via a translator hook that maps real → obf.
 *
 * The resolver uses these to pick the right overload when the caller passes
 * `argTypes`.
 */
package io.github.xiddoc.rosetta.core.resolver

private val PRIMITIVE_LETTERS = Regex("^[VZBCSIJFD]$")

/**
 * The single source of truth for JVM descriptor VOCABULARY, shared across the
 * neutral core ([toJvmDescriptor]) and the layer-4 reflection bridge
 * (`xposed/JvmDescriptors`, which delegates here). Centralising it means the
 * two sides cannot fork their primitive table or object-element rendering.
 *
 * It is reflection-free (no `Class<*>`), so it stays in `:core`: callers that
 * have a reflected type map it to a bare primitive name / binary class name
 * first and look it up here.
 */
public object Descriptors {
    /** Well-known Java primitive names → their single-letter JVM descriptors. */
    public val PRIMITIVE_DESCRIPTORS: Map<String, String> =
        mapOf(
            "void" to "V",
            "boolean" to "Z",
            "byte" to "B",
            "char" to "C",
            "short" to "S",
            "int" to "I",
            "long" to "J",
            "float" to "F",
            "double" to "D",
        )

    /** The single-letter descriptor for a primitive by [name], or null if not a primitive. */
    public fun primitive(name: String): String? = PRIMITIVE_DESCRIPTORS[name]

    /**
     * Render an object-type descriptor from a [binaryName] (dotted or already
     * slashed): `android.os.Bundle` → `Landroid/os/Bundle;`.
     */
    public fun objectDescriptor(binaryName: String): String = "L" + binaryName.replace('.', '/') + ";"
}

/**
 * Convert a user-facing type name to its JVM descriptor.
 *
 * Rules (identical to the TS implementation):
 *   - Primitive (`int`, `boolean`, …) → `I`, `Z`, …
 *   - Array (`int[]`, `java.lang.String[][]`) → `[` + recursion.
 *   - Already a descriptor (`L…;`, `[…`, or a lone primitive letter) →
 *     returned as-is (escape hatch for callers passing raw descriptors).
 *   - Class (`android.os.Bundle`, `IFoo`) → `Lpath/to/Cls;` after running
 *     the bare name through [translate] (real → obf).
 *
 * [translate] only ever sees the bare class name (no wrappers, no array
 * prefix) and returns the bare name; the wrappers are added here.
 */
public fun toJvmDescriptor(
    typeName: String,
    translate: (String) -> String,
): String {
    require(typeName.isNotEmpty()) { "empty type name" }

    // Already-descriptor passthrough.
    if (typeName.startsWith("L") && typeName.endsWith(";")) return typeName
    if (typeName.startsWith("[")) return typeName

    // Lone primitive letter.
    if (typeName.length == 1 && PRIMITIVE_LETTERS.matches(typeName)) return typeName

    // Array form: peel a trailing `[]` pair.
    if (typeName.endsWith("[]")) {
        return "[" + toJvmDescriptor(typeName.dropLast(2), translate)
    }

    // Primitive by name.
    Descriptors.primitive(typeName)?.let { return it }

    // Class name → translate, then wrap (shared object-element rendering).
    return Descriptors.objectDescriptor(translate(typeName))
}

/**
 * Split a JVM method signature's argument list into descriptors.
 *
 * `(Landroid/os/Bundle;Lbbbb;I)V` → `["Landroid/os/Bundle;", "Lbbbb;", "I"]`.
 *
 * Throws on a malformed signature — that's a map-authoring bug, surfaced
 * loudly rather than silently producing a no-match.
 */
public fun parseSignatureArgs(signature: String): List<String> {
    require(signature.startsWith("(")) { "signature must start with '(': $signature" }
    val close = signature.indexOf(')')
    require(close >= 0) { "signature missing ')': $signature" }

    val args = signature.substring(1, close)
    val out = mutableListOf<String>()
    var i = 0
    while (i < args.length) {
        when (args[i]) {
            '[' -> {
                var j = i
                while (j < args.length && args[j] == '[') j++
                require(j < args.length) { "signature: array prefix without element: $signature" }
                if (args[j] == 'L') {
                    val semi = args.indexOf(';', j)
                    require(semi >= 0) { "signature: unterminated object type: $signature" }
                    out += args.substring(i, semi + 1)
                    i = semi + 1
                } else {
                    // Array of a primitive: the element char must be in the
                    // closed primitive set (mirrors the bare-primitive branch
                    // and the Frida twin).
                    val elem = args[j]
                    require(PRIMITIVE_LETTERS.matches(elem.toString())) {
                        "signature: unknown array element char '$elem': $signature"
                    }
                    out += args.substring(i, j + 1)
                    i = j + 1
                }
            }
            'L' -> {
                val semi = args.indexOf(';', i)
                require(semi >= 0) { "signature: unterminated object type: $signature" }
                out += args.substring(i, semi + 1)
                i = semi + 1
            }
            else -> {
                // Primitive single-letter descriptor. Validate against the
                // closed primitive set (matches the Frida twin, which throws on
                // an unknown char): a bare `Q`, `X`, etc. is a malformed
                // descriptor, surfaced loudly rather than emitted verbatim.
                val ch = args[i]
                require(PRIMITIVE_LETTERS.matches(ch.toString())) {
                    "signature: unknown descriptor char '$ch': $signature"
                }
                out += ch.toString()
                i++
            }
        }
    }
    return out
}
