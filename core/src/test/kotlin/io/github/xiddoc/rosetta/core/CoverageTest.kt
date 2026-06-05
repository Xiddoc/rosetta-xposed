/*
 * Coverage-completing tests for the neutral core.
 *
 * These exercise the error/guard/edge branches that the happy-path tests in
 * CoreTest and the conformance suite don't reach: every validation issue, the
 * resolver's caching + override + invalidate + reverse-lookup paths, all of
 * the signature parser/descriptor edge cases, and the MethodOverloads guard +
 * its non-JSON serializer error paths. Together with CoreTest they bring the
 * core to 100% line + branch coverage (enforced by koverVerify).
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.FieldEntry
import io.github.xiddoc.rosetta.core.model.MethodEntry
import io.github.xiddoc.rosetta.core.model.MethodOverloads
import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.resolver.Resolver
import io.github.xiddoc.rosetta.core.resolver.parseSignatureArgs
import io.github.xiddoc.rosetta.core.resolver.toJvmDescriptor
import io.github.xiddoc.rosetta.core.version.VersionMatch
import kotlinx.serialization.properties.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CoverageTest {
    // A small map with one class, one single-overload method, one
    // multi-overload method, and one field — enough to drive every resolver
    // path below.
    private val map =
        RosettaMap(
            schemaVersion = 2,
            app = "com.example.app",
            version = "1.0.0",
            versionCode = 100,
            classes =
                mapOf(
                    "com.example.Foo" to
                        ClassEntry(
                            obfuscated = "a",
                            methods =
                                mapOf(
                                    "single" to
                                        MethodOverloads(
                                            listOf(
                                                MethodEntry("m", "()V", aidlTxn = 7, static = true),
                                            ),
                                        ),
                                    "over" to
                                        MethodOverloads(
                                            listOf(
                                                MethodEntry("n", "(I)V"),
                                                MethodEntry("n", "(J)V"),
                                            ),
                                        ),
                                ),
                            fields =
                                mapOf(
                                    "id" to FieldEntry("f", "Ljava/lang/String;"),
                                    "COUNT" to FieldEntry("g", "I", static = true),
                                ),
                        ),
                    // A class with neither `methods` nor `fields` map, to
                    // exercise the null-collection arms of resolveMethod/Field.
                    "com.example.Bare" to ClassEntry(obfuscated = "b"),
                ),
        )

    // ---- MapLoader.validate: every individual issue + the multi-issue path.

    @Test
    fun `validate rejects a blank app`() {
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map.copy(app = "")) }
        assertTrue(ex.issues.any { it.path == "app" })
    }

    @Test
    fun `validate rejects a blank version`() {
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map.copy(version = " ")) }
        assertTrue(ex.issues.any { it.path == "version" })
    }

    @Test
    fun `validate rejects a negative version_code`() {
        val ex = assertFailsWith<MapValidationException> { MapLoader.validate(map.copy(versionCode = -1)) }
        assertTrue(ex.issues.any { it.path == "version_code" })
    }

    @Test
    fun `validate reports multiple issues at once (plural summary)`() {
        val ex =
            assertFailsWith<MapValidationException> {
                MapLoader.validate(map.copy(schemaVersion = 1, app = "", version = ""))
            }
        // 3 issues exercises the `${issues.size} issues` plural branch.
        assertTrue(ex.issues.size >= 3)
        assertTrue(ex.message!!.contains("issues"))
    }

    @Test
    fun `validate returns the map unchanged on success`() {
        assertSame(map, MapLoader.validate(map))
    }

    @Test
    fun `fromJson carries the parse exception as the cause`() {
        val ex = assertFailsWith<MapValidationException> { MapLoader.fromJson("{ broken") }
        assertEquals(1, ex.issues.size)
        assertTrue(ex.cause is kotlinx.serialization.SerializationException)
    }

    // ---- Resolver: caching, miss paths, overrides, invalidate, reverse.

    @Test
    fun `resolveClass caches and a miss throws ResolveException`() {
        val resolver = Resolver(map)
        val first = resolver.resolveClass("com.example.Foo")
        // Second call hits the memoized cache (same instance).
        assertSame(first, resolver.resolveClass("com.example.Foo"))
        assertFailsWith<ResolveException> { resolver.resolveClass("com.example.Nope") }
    }

    @Test
    fun `resolveMethod caches, misses, and rejects an unmatched overload`() {
        val resolver = Resolver(map)
        val m = resolver.resolveMethod("com.example.Foo", "single")
        assertSame(m, resolver.resolveMethod("com.example.Foo", "single"))
        assertEquals("m", m.obfName)

        // Unknown method name → ResolveException.
        assertFailsWith<ResolveException> { resolver.resolveMethod("com.example.Foo", "ghost") }

        // argTypes that match no overload → ResolveException.
        assertFailsWith<ResolveException> {
            resolver.resolveMethod("com.example.Foo", "over", listOf("double"))
        }

        // A class with no `methods` map at all → the null-collection arm.
        assertFailsWith<ResolveException> { resolver.resolveMethod("com.example.Bare", "any") }
    }

    @Test
    fun `resolveMethod with argTypes selects the right overload and orders it first`() {
        val resolver = Resolver(map)
        val picked = resolver.resolveMethod("com.example.Foo", "over", listOf("long"))
        assertEquals("(J)V", picked.signature)
        // The selected overload is first; the other follows.
        assertEquals("(J)V", picked.allOverloads.first().signature)
        assertEquals(2, picked.allOverloads.size)
    }

    @Test
    fun `resolveMethod surfaces static flag and aidl transaction code`() {
        val resolver = Resolver(map)
        val m = resolver.resolveMethod("com.example.Foo", "single")
        assertTrue(m.static)
        assertEquals(7, m.aidlTxn)
        // The multi-overload method is non-static (static == null → false).
        assertTrue(!resolver.resolveMethod("com.example.Foo", "over", listOf("int")).static)
    }

    @Test
    fun `resolveField caches, surfaces static, and a miss throws`() {
        val resolver = Resolver(map)
        val f = resolver.resolveField("com.example.Foo", "id")
        assertSame(f, resolver.resolveField("com.example.Foo", "id"))
        assertEquals("f", f.obfName)
        // Non-static field (static == null → false) vs an explicit static one.
        assertTrue(!f.static)
        assertTrue(resolver.resolveField("com.example.Foo", "COUNT").static)
        assertFailsWith<ResolveException> { resolver.resolveField("com.example.Foo", "ghost") }
        // A class with no `fields` map at all → the null-collection arm.
        assertFailsWith<ResolveException> { resolver.resolveField("com.example.Bare", "any") }
    }

    @Test
    fun `override takes precedence, invalidates caches, and updates the reverse index`() {
        val resolver = Resolver(map)
        assertEquals("a", resolver.resolveClass("com.example.Foo").obfName)
        // hasClass true for a known name, false for an unknown one.
        assertTrue(resolver.hasClass("com.example.Foo"))
        assertTrue(!resolver.hasClass("com.example.Ghost"))

        // Register an override for an as-yet-unknown class, plus re-point Foo.
        resolver.override("com.example.Ghost", ClassEntry(obfuscated = "g"))
        assertTrue(resolver.hasClass("com.example.Ghost"))
        assertEquals("g", resolver.resolveClass("com.example.Ghost").obfName)

        // Overriding Foo must invalidate its cached class/method/field entries.
        resolver.resolveMethod("com.example.Foo", "single")
        resolver.resolveField("com.example.Foo", "id")
        resolver.override(
            "com.example.Foo",
            ClassEntry(
                obfuscated = "z",
                methods = mapOf("single" to MethodOverloads(listOf(MethodEntry("m2", "()V")))),
                fields = mapOf("id" to FieldEntry("f2", "Ljava/lang/String;")),
            ),
        )
        assertEquals("z", resolver.resolveClass("com.example.Foo").obfName)
        assertEquals("m2", resolver.resolveMethod("com.example.Foo", "single").obfName)
        assertEquals("f2", resolver.resolveField("com.example.Foo", "id").obfName)

        // Reverse lookup resolves obf → real, and is null for an unknown obf.
        assertEquals("com.example.Ghost", resolver.reverseLookup("g"))
        assertNull(resolver.reverseLookup("nope"))
    }

    @Test
    fun `translateType maps overrides and map entries, passes through the rest`() {
        val resolver = Resolver(map)
        // From the map.
        assertEquals("a", resolver.translateType("com.example.Foo"))
        // From an override (override branch).
        resolver.override("com.example.Bar", ClassEntry(obfuscated = "b"))
        assertEquals("b", resolver.translateType("com.example.Bar"))
        // Unmapped framework type passes through unchanged.
        assertEquals("android.os.Bundle", resolver.translateType("android.os.Bundle"))
    }

    // ---- Signature helpers: every edge branch.

    @Test
    fun `toJvmDescriptor rejects an empty type name`() {
        assertFailsWith<IllegalArgumentException> { toJvmDescriptor("") { it } }
    }

    @Test
    fun `toJvmDescriptor passes through array and lone-primitive-letter descriptors`() {
        assertEquals("[Lcom/x;", toJvmDescriptor("[Lcom/x;") { error("no translate") })
        assertEquals("I", toJvmDescriptor("I") { error("no translate") })
        // `void` keyword maps to V (the primitive-by-name branch).
        assertEquals("V", toJvmDescriptor("void") { it })
    }

    @Test
    fun `toJvmDescriptor treats L-prefixed-but-unterminated and unknown single letters as class names`() {
        // Starts with 'L' but does not end with ';' → NOT a descriptor; it is
        // a (degenerate) class name, so it gets wrapped (the && short-circuit's
        // false arm). The translator receives the bare name.
        assertEquals("LLfoo;", toJvmDescriptor("Lfoo") { it })
        // A single non-primitive letter ('X') is a one-char class name, not a
        // lone primitive-letter descriptor (the length==1 && regex false arm).
        assertEquals("LX;", toJvmDescriptor("X") { it })
    }

    @Test
    fun `parseSignatureArgs rejects missing close paren`() {
        assertFailsWith<IllegalArgumentException> { parseSignatureArgs("(I") }
    }

    @Test
    fun `parseSignatureArgs rejects an array prefix with no element`() {
        assertFailsWith<IllegalArgumentException> { parseSignatureArgs("([)V") }
    }

    @Test
    fun `parseSignatureArgs rejects an unterminated object type`() {
        // Bare `L...` with no `;`.
        assertFailsWith<IllegalArgumentException> { parseSignatureArgs("(Lcom/x)V") }
        // Array-of-`L...` with no `;`.
        assertFailsWith<IllegalArgumentException> { parseSignatureArgs("([Lcom/x)V") }
    }

    @Test
    fun `parseSignatureArgs handles an array of objects`() {
        assertEquals(listOf("[Lcom/x;"), parseSignatureArgs("([Lcom/x;)V"))
    }

    // ---- VersionMatch: the all-null return path.

    @Test
    fun `version selection returns null when neither code nor label matches`() {
        val registry = mapOf("1.0.0" to map)
        // Both null → falls through to the final `return null`.
        assertNull(VersionMatch.select(registry))
        // A label miss after no code given also returns null.
        assertNull(VersionMatch.select(registry, versionLabel = "9.9.9"))
        // Code given but no map carries it, then the label DOES match → the
        // code block falls through and the label branch wins.
        val byLabel = VersionMatch.select(registry, versionCode = 999, versionLabel = "1.0.0")
        assertEquals("label", byLabel!!.matchedBy)
        // A matching code → the firstOrNull predicate's true arm + early return.
        val byCode = VersionMatch.select(registry, versionCode = 100)
        assertEquals("version_code", byCode!!.matchedBy)
        // Code given, no match, no label → reaches the final `return null`
        // via the `versionLabel != null` false arm.
        assertNull(VersionMatch.select(registry, versionCode = 999))
        // Code given, no match, label given but also no match → both blocks
        // fall through to null.
        assertNull(VersionMatch.select(registry, versionCode = 999, versionLabel = "nope"))
    }

    // ---- MethodOverloads + its serializer.

    @Test
    fun `MethodOverloads rejects an empty overload list`() {
        assertFailsWith<IllegalArgumentException> { MethodOverloads(emptyList()) }
    }

    @Test
    fun `MethodOverloads singleOrNull reflects the count`() {
        assertEquals("m", MethodOverloads(listOf(MethodEntry("m", "()V"))).singleOrNull?.obfuscated)
        assertNull(MethodOverloads(listOf(MethodEntry("a", "()V"), MethodEntry("b", "(I)V"))).singleOrNull)
    }

    @Test
    fun `MethodOverloads serializer rejects a non-JSON encoder`() {
        // Properties is a non-JSON format → triggers the encoder guard's
        // `error("...can only be written to JSON")` branch.
        assertFailsWith<IllegalStateException> {
            Properties.encodeToMap(MethodOverloads.serializer(), MethodOverloads(listOf(MethodEntry("m", "()V"))))
        }
    }

    @Test
    fun `MethodOverloads serializer rejects a non-JSON decoder`() {
        assertFailsWith<IllegalStateException> {
            Properties.decodeFromMap(MethodOverloads.serializer(), emptyMap())
        }
    }

    @Test
    fun `TargetPolicyException carries name, target, reason and renders a message`() {
        val ex =
            TargetPolicyException(
                name = "com.example.app.RemoteClient",
                target = "java.lang.Runtime",
                reason = "on the reserved denylist",
            )
        assertEquals("com.example.app.RemoteClient", ex.name)
        assertEquals("java.lang.Runtime", ex.target)
        assertEquals("on the reserved denylist", ex.reason)
        assertTrue(ex is RosettaException)
        val message = ex.message
        assertTrue(message != null && message.contains("java.lang.Runtime"))
        assertTrue(message.contains("com.example.app.RemoteClient"))
        assertTrue(message.contains("on the reserved denylist"))
    }
}
