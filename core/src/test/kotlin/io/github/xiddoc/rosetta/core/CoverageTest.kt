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
import io.github.xiddoc.rosetta.core.resolver.DiscoveredClass
import io.github.xiddoc.rosetta.core.resolver.Resolver
import io.github.xiddoc.rosetta.core.resolver.parseSignatureArgs
import io.github.xiddoc.rosetta.core.resolver.toJvmDescriptor
import io.github.xiddoc.rosetta.core.version.MapRegistry
import io.github.xiddoc.rosetta.core.version.MatchedBy
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
            schemaVersion = 3,
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
    fun `ResolveException carries the typed ResolveTarget discriminator`() {
        val resolver = Resolver(map)
        val cls = assertFailsWith<ResolveException> { resolver.resolveClass("com.example.Nope") }
        assertEquals(ResolveTarget.CLASS, cls.target)
        val method = assertFailsWith<ResolveException> { resolver.resolveMethod("com.example.Foo", "ghost") }
        assertEquals(ResolveTarget.METHOD, method.target)
        val field = assertFailsWith<ResolveException> { resolver.resolveField("com.example.Foo", "ghost") }
        assertEquals(ResolveTarget.FIELD, field.target)
    }

    @Test
    fun `miss messages use the canonical wording (xposed#32)`() {
        // Aligned to the rosetta-frida twin: class -> "class '<name>' not
        // found"; method/field -> "<kind> '<name>' not found on class
        // '<class>'". Asserts the load-bearing fragments, not the full string,
        // so app@version formatting stays free to evolve.
        val resolver = Resolver(map)
        val cls = assertFailsWith<ResolveException> { resolver.resolveClass("com.example.Nope") }
        assertTrue(cls.message!!.contains("class 'com.example.Nope' not found"), cls.message)

        val method = assertFailsWith<ResolveException> { resolver.resolveMethod("com.example.Foo", "ghost") }
        assertTrue(
            method.message!!.contains("method 'ghost' not found on class 'com.example.Foo'"),
            method.message,
        )

        val field = assertFailsWith<ResolveException> { resolver.resolveField("com.example.Foo", "ghost") }
        assertTrue(
            field.message!!.contains("field 'ghost' not found on class 'com.example.Foo'"),
            field.message,
        )
    }

    @Test
    fun `an unmapped real-name arg type raises a distinct UnknownArgTypeException`() {
        // A class with one overload taking an obf-mapped arg type `Lobf;` (the
        // obf of a mapped real name "com.example.Arg"), so a KNOWN real arg type
        // would match, but an UNKNOWN one (not in the map) should be flagged
        // distinctly rather than misattributed to "no overload matches".
        val m =
            RosettaMap(
                schemaVersion = 3,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                classes =
                    mapOf(
                        "com.example.Arg" to ClassEntry(obfuscated = "obf"),
                        "com.example.Holder" to
                            ClassEntry(
                                obfuscated = "h",
                                methods =
                                    mapOf(
                                        "take" to MethodOverloads(listOf(MethodEntry("t", "(Lobf;)V"))),
                                    ),
                            ),
                    ),
            )
        val resolver = Resolver(m)
        // The mapped real arg type resolves fine (translateType -> "obf").
        assertEquals("t", resolver.resolveMethod("com.example.Holder", "take", listOf("com.example.Arg")).obfName)

        // An UNMAPPED app-class arg type → distinct UnknownArgTypeException,
        // which is a ResolveException so existing handling still catches it.
        val ex =
            assertFailsWith<UnknownArgTypeException> {
                resolver.resolveMethod("com.example.Holder", "take", listOf("com.example.NotMapped"))
            }
        assertEquals("com.example.NotMapped", ex.argType)
        // F4: classScope is a required (non-defaulted) field on this subtype —
        // assert it so a swap with `app` in overloadMissException would be caught.
        assertEquals("com.example.Holder", ex.classScope)
        assertTrue(ex is ResolveException)
    }

    @Test
    fun `a known mapped arg type that no overload matches is a plain no-match`() {
        // Holder.take takes (Lobf;)V. Passing a DIFFERENT but mapped class
        // (com.example.Other -> oth) is a known class, so it is NOT flagged as
        // an unknown arg type — it is a legitimate no-overload-match.
        val m =
            RosettaMap(
                schemaVersion = 3,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                classes =
                    mapOf(
                        "com.example.Arg" to ClassEntry(obfuscated = "obf"),
                        "com.example.Other" to ClassEntry(obfuscated = "oth"),
                        "com.example.Holder" to
                            ClassEntry(
                                obfuscated = "h",
                                methods = mapOf("take" to MethodOverloads(listOf(MethodEntry("t", "(Lobf;)V")))),
                            ),
                    ),
            )
        val resolver = Resolver(m)
        val ex =
            assertFailsWith<ResolveException> {
                resolver.resolveMethod("com.example.Holder", "take", listOf("com.example.Other"))
            }
        assertTrue(ex !is UnknownArgTypeException)
    }

    @Test
    fun `a framework arg type that no overload declares is still flagged precisely`() {
        // java.lang.String isn't in the map and the only overload takes Lobf;,
        // so the precise unknown-arg error fires (a ResolveException subtype).
        val m =
            RosettaMap(
                schemaVersion = 3,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                classes =
                    mapOf(
                        "com.example.Holder" to
                            ClassEntry(
                                obfuscated = "h",
                                methods = mapOf("take" to MethodOverloads(listOf(MethodEntry("t", "(Lobf;)V")))),
                            ),
                    ),
            )
        val resolver = Resolver(m)
        assertFailsWith<UnknownArgTypeException> {
            resolver.resolveMethod("com.example.Holder", "take", listOf("java.lang.String"))
        }
    }

    @Test
    fun `a wrong primitive overload still gives a plain no-match ResolveException`() {
        // `over` takes (I)V/(J)V; asking for (double) is a legit no-match (the
        // arg type is a known primitive), NOT an unknown-arg-type error.
        val resolver = Resolver(map)
        val ex =
            assertFailsWith<ResolveException> {
                resolver.resolveMethod("com.example.Foo", "over", listOf("double"))
            }
        assertTrue(ex !is UnknownArgTypeException)
    }

    @Test
    fun `non-class-name arg type forms are plain no-matches, not unknown-arg errors`() {
        // Each of these arg-type FORMS short-circuits the unknown-arg detector
        // (raw L-descriptor, array-prefix descriptor, []-suffix, single-letter
        // primitive descriptor), so a miss is a plain ResolveException — not a
        // misattributed UnknownArgTypeException.
        val resolver = Resolver(map)
        // `single` is ()V (zero args), so any single arg is a guaranteed
        // no-match regardless of the arg's descriptor.
        for (argType in listOf("Lcom/example/Whatever;", "[I", "int[]", "I")) {
            val ex =
                assertFailsWith<ResolveException> {
                    resolver.resolveMethod("com.example.Foo", "single", listOf(argType))
                }
            assertTrue(ex !is UnknownArgTypeException, "unexpected unknown-arg error for '$argType'")
        }
    }

    @Test
    fun `an unmapped arg type whose descriptor a sibling overload declares is a plain no-match`() {
        // Holder.take has overloads (Lobf;)V and (Lother;)V. Asking for arg
        // types that translate to an unknown class whose descriptor neither
        // overload uses is the unknown-arg case; but if the descriptor IS
        // declared by some overload the call is a normal disambiguation (here we
        // pass two args so neither single-arg overload matches, yet the lone
        // arg's descriptor IS declared → plain no-match, exercising the
        // wanted[i] in knownDescriptors branch).
        val m =
            RosettaMap(
                schemaVersion = 3,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                classes =
                    mapOf(
                        "com.example.Holder" to
                            ClassEntry(
                                obfuscated = "h",
                                methods =
                                    mapOf(
                                        "take" to
                                            MethodOverloads(
                                                listOf(
                                                    MethodEntry("t", "(Lobf;Lobf;)V"),
                                                ),
                                            ),
                                    ),
                            ),
                    ),
            )
        val resolver = Resolver(m)
        // One arg of a raw descriptor that the overload declares (Lobf;), so the
        // arg's wanted descriptor IS in knownDescriptors → plain no-match.
        val ex =
            assertFailsWith<ResolveException> {
                resolver.resolveMethod("com.example.Holder", "take", listOf("Lobf;"))
            }
        assertTrue(ex !is UnknownArgTypeException)
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
        // Tri-state: an asserted true stays true (not folded away).
        assertEquals(true, m.static)
        assertEquals(7, m.aidlTxn)
        // The multi-overload method omits `static`, so it stays null
        // (asserted-vs-unknown preserved — NOT folded to false).
        assertNull(resolver.resolveMethod("com.example.Foo", "over", listOf("int")).static)
    }

    @Test
    fun `resolveField caches, surfaces static, and a miss throws`() {
        val resolver = Resolver(map)
        val f = resolver.resolveField("com.example.Foo", "id")
        assertSame(f, resolver.resolveField("com.example.Foo", "id"))
        assertEquals("f", f.obfName)
        // Unknown staticness stays null (not folded to false) vs an explicit one.
        assertNull(f.static)
        assertEquals(true, resolver.resolveField("com.example.Foo", "COUNT").static)
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
        resolver.override(DiscoveredClass(realName = "com.example.Ghost", obfName = "g"))
        assertTrue(resolver.hasClass("com.example.Ghost"))
        assertEquals("g", resolver.resolveClass("com.example.Ghost").obfName)

        // Overriding Foo must invalidate its cached class/method/field entries.
        resolver.resolveMethod("com.example.Foo", "single")
        resolver.resolveField("com.example.Foo", "id")
        resolver.override(
            DiscoveredClass(
                realName = "com.example.Foo",
                obfName = "z",
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
        // The override re-pointed Foo from "a" to "z": the new obf resolves to
        // Foo, and the STALE old obf entry is cleaned (no longer maps to Foo).
        assertEquals("com.example.Foo", resolver.reverseLookup("z"))
        assertNull(resolver.reverseLookup("a"))
    }

    @Test
    fun `reverse index is first-write-wins on a build-time obf collision`() {
        // Two real names map to the same obf short name "dup" — a degenerate
        // map. Policy: the first real name (by sorted construction) owns the
        // reverse entry deterministically, not last-write-wins.
        val colliding =
            RosettaMap(
                schemaVersion = 3,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                // LinkedHashMap preserves insertion order; Alpha is inserted first.
                classes =
                    linkedMapOf(
                        "com.example.Alpha" to ClassEntry(obfuscated = "dup"),
                        "com.example.Beta" to ClassEntry(obfuscated = "dup"),
                    ),
            )
        val resolver = Resolver(colliding)
        // First-write-wins: Alpha owns "dup"; Beta's colliding write is ignored.
        assertEquals("com.example.Alpha", resolver.reverseLookup("dup"))
        // Both forward resolutions still work (the forward map is unaffected).
        assertEquals("dup", resolver.resolveClass("com.example.Alpha").obfName)
        assertEquals("dup", resolver.resolveClass("com.example.Beta").obfName)
    }

    @Test
    fun `reverse index first-write-wins is insertion-order, the canonical cross-client policy`() {
        // xposed#14 M2: the winner is the FIRST entry by INSERTION order, not by
        // any sort. Inserting Beta first here (the reverse of the sibling test)
        // makes Beta own "dup", proving the policy keys off map iteration order —
        // the deterministic first-write-wins both Rosetta clients standardize on.
        val colliding =
            RosettaMap(
                schemaVersion = 3,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                classes =
                    linkedMapOf(
                        "com.example.Beta" to ClassEntry(obfuscated = "dup"),
                        "com.example.Alpha" to ClassEntry(obfuscated = "dup"),
                    ),
            )
        val resolver = Resolver(colliding)
        assertEquals("com.example.Beta", resolver.reverseLookup("dup"))
    }

    @Test
    fun `override to the same obf keeps the reverse entry intact`() {
        // previousObf == entry.obfuscated → the stale-clean branch is skipped.
        val base =
            RosettaMap(
                schemaVersion = 3,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                classes = mapOf("com.example.Alpha" to ClassEntry(obfuscated = "x")),
            )
        val resolver = Resolver(base)
        resolver.override(DiscoveredClass(realName = "com.example.Alpha", obfName = "x", extends = "zzzz"))
        assertEquals("com.example.Alpha", resolver.reverseLookup("x"))
        assertEquals("zzzz", resolver.resolveClass("com.example.Alpha").extends)
    }

    @Test
    fun `override does not clean a stale obf now owned by another real name`() {
        // Alpha owns "x". Re-point Alpha to "y", but first have "x" reassigned
        // to Beta via an override so reverseClassIndex["x"] != Alpha; the
        // stale-clean must NOT remove Beta's entry.
        val base =
            RosettaMap(
                schemaVersion = 3,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                classes = mapOf("com.example.Alpha" to ClassEntry(obfuscated = "x")),
            )
        val resolver = Resolver(base)
        // Beta takes over "x" (intentional re-point).
        resolver.override(DiscoveredClass(realName = "com.example.Beta", obfName = "x"))
        assertEquals("com.example.Beta", resolver.reverseLookup("x"))
        // Now move Alpha to "y": its previous obf "x" is no longer owned by
        // Alpha, so the stale-clean leaves Beta's "x" entry untouched.
        resolver.override(DiscoveredClass(realName = "com.example.Alpha", obfName = "y"))
        assertEquals("com.example.Alpha", resolver.reverseLookup("y"))
        assertEquals("com.example.Beta", resolver.reverseLookup("x"))
    }

    @Test
    fun `override re-pointing to a colliding obf takes the reverse entry`() {
        // An override is the documented exception to first-write-wins: it is an
        // intentional re-point, so it claims the obf even on a collision.
        val base =
            RosettaMap(
                schemaVersion = 3,
                app = "com.example.app",
                version = "1.0.0",
                versionCode = 100,
                classes = mapOf("com.example.Alpha" to ClassEntry(obfuscated = "x")),
            )
        val resolver = Resolver(base)
        assertEquals("com.example.Alpha", resolver.reverseLookup("x"))
        // Override Beta onto the SAME obf "x" — the intentional re-point wins.
        resolver.override(DiscoveredClass(realName = "com.example.Beta", obfName = "x"))
        assertEquals("com.example.Beta", resolver.reverseLookup("x"))
    }

    @Test
    fun `translateType maps overrides and map entries, passes through the rest`() {
        val resolver = Resolver(map)
        // From the map.
        assertEquals("a", resolver.translateType("com.example.Foo"))
        // From an override (override branch).
        resolver.override(DiscoveredClass(realName = "com.example.Bar", obfName = "b"))
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
        val registry = MapRegistry.of(map)
        // Both null → falls through to the final `return null`.
        assertNull(VersionMatch.select(registry))
        // A label miss after no code given also returns null.
        assertNull(VersionMatch.select(registry, versionLabel = "9.9.9"))
        // Code given but no map carries it, then the label DOES match → the
        // code block falls through and the label branch wins.
        val byLabel = VersionMatch.select(registry, versionCode = 999, versionLabel = "1.0.0")
        assertEquals(MatchedBy.LABEL, byLabel!!.matchedBy)
        // A matching code → the O(1) version_code index hit + early return.
        val byCode = VersionMatch.select(registry, versionCode = 100)
        assertEquals(MatchedBy.VERSION_CODE, byCode!!.matchedBy)
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
