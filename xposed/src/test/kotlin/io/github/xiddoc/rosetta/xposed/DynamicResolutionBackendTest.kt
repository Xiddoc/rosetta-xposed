/*
 * Dynamic (self-healing) backend tests (B.1).
 *
 * These run on a plain JVM with a [FakeDexKitIndex] in place of the real
 * device-only DexKit adapter, so the full discovery LOGIC is exercised with no
 * device and no `org.luckypray:dexkit` on the classpath. They cover:
 *
 *   - each class-locating strategy success (AIDL, anchors, superclass);
 *   - method discovery within a found class;
 *   - misses + partial discovery → DiscoveryException (fail-closed);
 *   - memoization (a discovered class is scanned at most once);
 *   - provenance emit (tool = "rosetta-runtime-discovered");
 *   - the ReDoS / bounds cases (over-length signature, over-count anchors, a
 *     pathological backtracking pattern that RE2 handles promptly);
 *   - the discovery value types.
 *
 * The composite (static-first) wiring, the C1 guard over a discovered FQN, and
 * the RosettaXposed.fromMapWithDiscovery construction path live in the sibling
 * CompositeDiscoveryWiringTest.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.model.ClassEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DynamicResolutionBackendTest {
    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"
    private val real = "com.example.RealClient"

    // ---- Strategy (a): AIDL descriptor --------------------------------------

    @Test
    fun `discovers a class by AIDL descriptor`() {
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val backend =
            DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;")))
        assertTrue(backend.canResolve(real))
        assertEquals(obf, backend.resolveClass(real).obfName)
    }

    // ---- Strategy (b): stable string anchors --------------------------------

    @Test
    fun `discovers a class by anchors`() {
        val anchors = listOf("login_token", "session_id")
        val index = FakeDexKitIndex(byAnchors = mapOf(anchors to obf))
        val backend = DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(anchors = anchors)))
        // canResolve over an anchors-only hint: aidl null, anchors non-empty.
        assertTrue(backend.canResolve(real))
        assertEquals(obf, backend.resolveClass(real).obfName)
    }

    // ---- Strategy (c): superclass narrowing ---------------------------------

    @Test
    fun `discovers a class by superclass`() {
        val index = FakeDexKitIndex(bySuper = mapOf("zzzz" to obf))
        val backend = DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(superclass = "zzzz")))
        // canResolve over a superclass-only hint: aidl null, anchors empty.
        assertTrue(backend.canResolve(real))
        assertEquals(obf, backend.resolveClass(real).obfName)
    }

    @Test
    fun `strategy order falls through aidl and anchors to superclass`() {
        // AIDL + anchors both miss; superclass hits. Confirms order (a)->(b)->(c).
        val index =
            FakeDexKitIndex(
                byAidl = emptyMap2(),
                byAnchors = emptyMap2List(),
                bySuper = mapOf("zzzz" to obf),
            )
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(
                    real to
                        DiscoveryHints(
                            aidlDescriptor = "Lcom/example/IMissing;",
                            anchors = listOf("nope"),
                            superclass = "zzzz",
                        ),
                ),
            )
        assertEquals(obf, backend.resolveClass(real).obfName)
    }

    private fun emptyMap2(): Map<String, String> = emptyMap()

    private fun emptyMap2List(): Map<List<String>, String> = emptyMap()

    // ---- Strategy (d): method discovery within a found class ----------------

    @Test
    fun `discovers a method within the located class`() {
        val index =
            FakeDexKitIndex(
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "c", "(Ljava/lang/String;)Ljava/lang/String;"))),
            )
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(
                    real to
                        DiscoveryHints(
                            aidlDescriptor = "Lcom/example/IFoo;",
                            methods =
                                listOf(
                                    MethodDiscoveryHint(
                                        realName = "single",
                                        descriptor = "(Ljava/lang/String;)Ljava/lang/String;",
                                    ),
                                ),
                        ),
                ),
            )
        val m = backend.resolveMethod(real, "single")
        assertEquals("c", m.obfName)
        assertEquals(obf, m.className)
    }

    @Test
    fun `resolveMethod honours matching argTypes on a discovered overload`() {
        // Liskov parity with the static backend: when the caller pins arg
        // types that match the single discovered overload, it resolves.
        val index =
            FakeDexKitIndex(
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "c", "(Ljava/lang/String;)Ljava/lang/String;"))),
            )
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(
                    real to
                        DiscoveryHints(
                            aidlDescriptor = "Lcom/example/IFoo;",
                            methods =
                                listOf(
                                    MethodDiscoveryHint(
                                        realName = "single",
                                        descriptor = "(Ljava/lang/String;)Ljava/lang/String;",
                                    ),
                                ),
                        ),
                ),
            )
        val m = backend.resolveMethod(real, "single", listOf("java.lang.String"))
        assertEquals("c", m.obfName)
    }

    @Test
    fun `resolveMethod translates real-name argTypes through the map to match a discovered overload`() {
        // F1: a caller passes a REAL app-class arg type ("com.example.RealArg")
        // and the map maps it to "obfArg"; the discovered overload's descriptor
        // carries the OBFUSCATED ref ("(LobfArg;)V"). With the real → obf
        // translator wired in, the translated descriptor matches and it
        // resolves — an identity translate would have spuriously thrown.
        val index =
            FakeDexKitIndex(
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "c", "(LobfArg;)V"))),
            )
        val backend =
            DynamicResolutionBackend(
                index = index,
                hints =
                    mapOf(
                        real to
                            DiscoveryHints(
                                aidlDescriptor = "Lcom/example/IFoo;",
                                methods = listOf(MethodDiscoveryHint(realName = "single", descriptor = "(LobfArg;)V")),
                            ),
                    ),
                translateType = { name -> if (name == "com.example.RealArg") "obfArg" else name },
            )
        val m = backend.resolveMethod(real, "single", listOf("com.example.RealArg"))
        assertEquals("c", m.obfName)
    }

    @Test
    fun `resolveMethod with a translator still fails closed on a genuine arg-type mismatch`() {
        // Same translator wiring, but the caller asks for an arg type the
        // discovered overload does not declare — the real → obf translation
        // must NOT mask a genuine mismatch.
        val index =
            FakeDexKitIndex(
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "c", "(LobfArg;)V"))),
            )
        val backend =
            DynamicResolutionBackend(
                index = index,
                hints =
                    mapOf(
                        real to
                            DiscoveryHints(
                                aidlDescriptor = "Lcom/example/IFoo;",
                                methods = listOf(MethodDiscoveryHint(realName = "single", descriptor = "(LobfArg;)V")),
                            ),
                    ),
                translateType = { name -> if (name == "com.example.RealArg") "obfArg" else name },
            )
        assertFailsWith<DiscoveryException> { backend.resolveMethod(real, "single", listOf("com.example.OtherArg")) }
    }

    @Test
    fun `resolveMethod fails closed when argTypes do not match the discovered overload`() {
        // The single discovered overload takes (String); the caller asks for
        // (int) — a wrong-overload request must NOT silently return the String
        // one (the Liskov bug). Fail closed.
        val index =
            FakeDexKitIndex(
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "c", "(Ljava/lang/String;)Ljava/lang/String;"))),
            )
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(
                    real to
                        DiscoveryHints(
                            aidlDescriptor = "Lcom/example/IFoo;",
                            methods =
                                listOf(
                                    MethodDiscoveryHint(
                                        realName = "single",
                                        descriptor = "(Ljava/lang/String;)Ljava/lang/String;",
                                    ),
                                ),
                        ),
                ),
            )
        assertFailsWith<DiscoveryException> { backend.resolveMethod(real, "single", listOf("int")) }
    }

    // ---- Misses + partial discovery → DiscoveryException --------------------

    @Test
    fun `a class miss fails closed`() {
        val index = FakeDexKitIndex()
        val backend = DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(superclass = "zzzz")))
        assertFailsWith<DiscoveryException> { backend.resolveClass(real) }
    }

    @Test
    fun `no hint for a real name fails closed`() {
        val backend = DynamicResolutionBackend(FakeDexKitIndex(), emptyMap())
        assertTrue(!backend.canResolve(real))
        assertFailsWith<DiscoveryException> { backend.resolveClass(real) }
    }

    @Test
    fun `a hint with no locating facet cannot resolve`() {
        val backend = DynamicResolutionBackend(FakeDexKitIndex(), mapOf(real to DiscoveryHints()))
        assertTrue(!backend.canResolve(real))
        assertFailsWith<DiscoveryException> { backend.resolveClass(real) }
    }

    @Test
    fun `class found but member not found is a partial discovery that fails closed`() {
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf), methods = emptyMap())
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(
                    real to
                        DiscoveryHints(
                            aidlDescriptor = "Lcom/example/IFoo;",
                            methods = listOf(MethodDiscoveryHint(realName = "single", descriptor = "()V")),
                        ),
                ),
            )
        assertFailsWith<DiscoveryException> { backend.resolveClass(real) }
    }

    @Test
    fun `resolveMethod for an unhinted method on a discovered class fails closed`() {
        // Class discovered (no method hints), then a method is requested.
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val backend =
            DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;")))
        assertFailsWith<DiscoveryException> { backend.resolveMethod(real, "absent") }
    }

    @Test
    fun `resolveMethod for a different name on a class with methods fails closed`() {
        // The class HAS a methods map (one hinted method) but a DIFFERENT name
        // is requested — exercises the non-null-methods, name-miss branch.
        val index =
            FakeDexKitIndex(
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "c", "(Ljava/lang/String;)Ljava/lang/String;"))),
            )
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(
                    real to
                        DiscoveryHints(
                            aidlDescriptor = "Lcom/example/IFoo;",
                            methods =
                                listOf(
                                    MethodDiscoveryHint(
                                        realName = "single",
                                        descriptor = "(Ljava/lang/String;)Ljava/lang/String;",
                                    ),
                                ),
                        ),
                ),
            )
        // "single" resolves; "other" does not.
        assertEquals("c", backend.resolveMethod(real, "single").obfName)
        assertFailsWith<DiscoveryException> { backend.resolveMethod(real, "other") }
    }

    @Test
    fun `resolveField always fails closed`() {
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val backend =
            DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;")))
        assertFailsWith<DiscoveryException> { backend.resolveField(real, "id") }
    }

    // ---- Memoization: a discovered class is scanned at most once ------------

    @Test
    fun `a discovered class is memoized so the index is queried once`() {
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val backend =
            DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;")))
        backend.resolveClass(real)
        val after = index.calls
        backend.resolveClass(real)
        assertEquals(after, index.calls)
    }

    // ---- Provenance emit ----------------------------------------------------

    @Test
    fun `discovery records a runtime-discovered source`() {
        val sink = MapDiscoverySink()
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;")),
                sink,
            )
        backend.resolveClass(real)

        assertEquals(1, sink.entries().size)
        assertEquals(real, sink.entries().single().realName)
        assertEquals(
            obf,
            sink
                .entries()
                .single()
                .entry.obfuscated,
        )
        assertEquals(
            RUNTIME_DISCOVERED_TOOL,
            sink
                .entries()
                .single()
                .entry.source,
        )

        val prov = sink.provenance()
        assertEquals("rosetta-runtime-discovered", prov.tool)
        assertEquals(1, prov.classes)
    }

    @Test
    fun `the NOOP sink records nothing`() {
        // Exercise the default NOOP sink's record() body.
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val backend =
            DynamicResolutionBackend(index, mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;")))
        // No throw, and nothing observable to record.
        assertEquals(obf, backend.resolveClass(real).obfName)
        DiscoverySink.NOOP.record(real, ClassEntry(obfuscated = obf))
    }

    @Test
    fun `discovered entry carries extends but not the anchors used to locate it`() {
        // schema_version: 4 made the map a pure real→obfuscated mapping: the
        // anchors that LOCATE a class are runtime-discovery evidence (a
        // DiscoveryHints facet), not a map field, so the synthesized ClassEntry
        // must NOT round-trip them. It still carries the pure-mapping `extends`.
        val anchors = listOf("a1", "a2")
        val index = FakeDexKitIndex(byAnchors = mapOf(anchors to obf))
        val sink = MapDiscoverySink()
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(real to DiscoveryHints(anchors = anchors, superclass = "zzzz")),
                sink,
            )
        backend.resolveClass(real)
        val entry = sink.entries().single().entry
        assertEquals("zzzz", entry.extends)
        assertEquals(obf, entry.obfuscated)
    }

    @Test
    fun `a descriptor-discovered entry has kind null (v4 dropped aidl_stub)`() {
        // The AIDL descriptor is still the strongest LOCATING strategy (it lives
        // in the DiscoveryHints), but schema_version: 4 removed the aidl_stub /
        // aidl_callback class kinds: the synthesized map ClassEntry is a pure
        // real→obfuscated mapping, so it carries no implied kind.
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val sink = MapDiscoverySink()
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;")),
                sink,
            )
        backend.resolveClass(real)
        assertNull(
            sink
                .entries()
                .single()
                .entry.kind,
        )
    }

    @Test
    fun `a non-aidl discovered entry has kind null`() {
        // Entries found by anchors or superclass only carry no implied kind.
        val anchors = listOf("tok1")
        val index = FakeDexKitIndex(byAnchors = mapOf(anchors to obf))
        val sink = MapDiscoverySink()
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(real to DiscoveryHints(anchors = anchors)),
                sink,
            )
        backend.resolveClass(real)
        assertNull(
            sink
                .entries()
                .single()
                .entry.kind,
        )
    }

    // ---- ReDoS / bounds cases (audit H4) ------------------------------------

    @Test
    fun `an over-length signature throws DiscoveryException before compiling`() {
        val tooLong = "a".repeat(SafePattern.MAX_SIGNATURE_LEN + 1)
        assertEquals(4097, tooLong.length)
        assertFailsWith<DiscoveryException> { SafePattern.compile(tooLong) }
        assertFailsWith<DiscoveryException> { SafePattern.checkLen(tooLong) }
    }

    @Test
    fun `an over-count anchor list throws DiscoveryException before compiling`() {
        val tooMany = (0..SafePattern.MAX_ANCHORS).map { "a$it" }
        assertEquals(1001, tooMany.size)
        assertFailsWith<DiscoveryException> { SafePattern.compileAll(tooMany) }
        assertFailsWith<DiscoveryException> { SafePattern.checkBounds(tooMany) }
    }

    @Test
    fun `a pathological backtracking pattern compiles promptly under RE2`() {
        // `(a+)+$` is catastrophic for a backtracking engine; RE2 is linear, so
        // this returns immediately and yields a com.google.re2j.Pattern.
        val pattern = SafePattern.compile("(a+)+$")
        assertTrue(pattern is com.google.re2j.Pattern)
        // And it actually runs in linear time against an adversarial input.
        val adversarial = "a".repeat(64) + "!"
        assertTrue(!pattern.matcher(adversarial).matches())
    }

    @Test
    fun `SafePattern signature cap is sourced from the core map cap (no drift)`() {
        // The signature-length cap must equal the canonical map-loader cap so the
        // runtime-discovered path and the static map share one budget. The
        // anchors cap is a discovery-only bound (schema_version: 4 dropped the
        // map `anchors` field — anchors are now DiscoveryHints evidence), so it
        // is SafePattern-owned and just has to be a sane positive bound.
        assertEquals(io.github.xiddoc.rosetta.core.MapLoader.MAX_SIGNATURE_LEN, SafePattern.MAX_SIGNATURE_LEN)
        assertEquals(1_000, SafePattern.MAX_ANCHORS)
    }

    @Test
    fun `SafePattern rejects a malformed RE2 expression`() {
        assertFailsWith<DiscoveryException> { SafePattern.compile("(unclosed") }
    }

    @Test
    fun `SafePattern compileAll compiles a small list`() {
        val patterns = SafePattern.compileAll(listOf("foo", "ba+r"))
        assertEquals(2, patterns.size)
    }

    @Test
    fun `over-bound anchors in a discovery hint fail closed via the backend`() {
        val tooMany = (0..SafePattern.MAX_ANCHORS).map { "a$it" }
        val backend = DynamicResolutionBackend(FakeDexKitIndex(), mapOf(real to DiscoveryHints(anchors = tooMany)))
        assertFailsWith<DiscoveryException> { backend.resolveClass(real) }
    }

    @Test
    fun `over-length aidl descriptor in a hint fails closed via the backend`() {
        val tooLong = "L" + "a".repeat(SafePattern.MAX_SIGNATURE_LEN) + ";"
        val backend = DynamicResolutionBackend(FakeDexKitIndex(), mapOf(real to DiscoveryHints(aidlDescriptor = tooLong)))
        assertFailsWith<DiscoveryException> { backend.resolveClass(real) }
    }

    @Test
    fun `over-length superclass in a hint fails closed via the backend`() {
        val tooLong = "a".repeat(SafePattern.MAX_SIGNATURE_LEN + 1)
        val backend = DynamicResolutionBackend(FakeDexKitIndex(), mapOf(real to DiscoveryHints(superclass = tooLong)))
        assertFailsWith<DiscoveryException> { backend.resolveClass(real) }
    }

    @Test
    fun `over-length method descriptor in a hint fails closed via the backend`() {
        val tooLong = "(" + "a".repeat(SafePattern.MAX_SIGNATURE_LEN) + ")"
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(
                    real to
                        DiscoveryHints(
                            aidlDescriptor = "Lcom/example/IFoo;",
                            methods = listOf(MethodDiscoveryHint(realName = "single", descriptor = tooLong)),
                        ),
                ),
            )
        assertFailsWith<DiscoveryException> { backend.resolveClass(real) }
    }

    @Test
    fun `over-count usingStrings in a method hint fails closed via the backend`() {
        val tooMany = (0..SafePattern.MAX_ANCHORS).map { "s$it" }
        val index = FakeDexKitIndex(byAidl = mapOf("Lcom/example/IFoo;" to obf))
        val backend =
            DynamicResolutionBackend(
                index,
                mapOf(
                    real to
                        DiscoveryHints(
                            aidlDescriptor = "Lcom/example/IFoo;",
                            methods = listOf(MethodDiscoveryHint(realName = "single", usingStrings = tooMany)),
                        ),
                ),
            )
        assertFailsWith<DiscoveryException> { backend.resolveClass(real) }
    }

    // ---- DiscoveryException value type --------------------------------------

    @Test
    fun `DiscoveryException carries message and optional cause`() {
        val cause = IllegalStateException("boom")
        assertEquals(cause, DiscoveryException("wrapped", cause).cause)
        assertNull(DiscoveryException("just a message").cause)
    }

    @Test
    fun `DiscoveryException and BindException are XposedBindingFailure`() {
        // The marker lets a module catch any layer-4 binding failure in one
        // clause without enumerating the concrete types.
        assertTrue(DiscoveryException("x") is XposedBindingFailure)
        assertTrue(BindException("y") is XposedBindingFailure)
    }

    @Test
    fun `MapDiscoverySink records concurrently without losing entries`() {
        // Append from many threads; the synchronized backing list must retain
        // every record (a plain ArrayList would drop or corrupt under races).
        val sink = MapDiscoverySink()
        val threads = 8
        val perThread = 250
        val workers =
            (0 until threads).map { t ->
                Thread {
                    repeat(perThread) { i ->
                        sink.record("com.example.C$t-$i", ClassEntry(obfuscated = "o$t$i"))
                    }
                }
            }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(threads * perThread, sink.entries().size)
        assertEquals(threads * perThread, sink.provenance().classes)
    }

    // ---- Value types --------------------------------------------------------

    @Test
    fun `MethodDiscoveryHint and DiscoveryHints expose their fields`() {
        val mh =
            MethodDiscoveryHint(
                realName = "m",
                descriptor = "()V",
                returnType = "V",
                paramTypes = listOf("I"),
                usingStrings = listOf("s"),
            )
        assertEquals("m", mh.realName)
        assertEquals("()V", mh.descriptor)
        assertEquals("V", mh.returnType)
        assertEquals(listOf("I"), mh.paramTypes)
        assertEquals(listOf("s"), mh.usingStrings)

        val h = DiscoveryHints(aidlDescriptor = "d", anchors = listOf("a"), superclass = "s", methods = listOf(mh))
        assertEquals("d", h.aidlDescriptor)
        assertEquals(listOf("a"), h.anchors)
        assertEquals("s", h.superclass)
        assertSame(mh, h.methods.single())
        assertTrue(h.canLocateClass)
    }

    @Test
    fun `DiscoveryConfig defaults to empty hints and the NOOP sink`() {
        val cfg = DiscoveryConfig()
        assertTrue(cfg.hints.isEmpty())
        assertSame(DiscoverySink.NOOP, cfg.sink)
        val custom = DiscoveryConfig(hints = mapOf(real to DiscoveryHints(superclass = "z")), sink = MapDiscoverySink())
        assertEquals(1, custom.hints.size)
        assertTrue(custom.sink is MapDiscoverySink)
    }

    @Test
    fun `MethodQuery and MethodMatch expose their fields`() {
        val q =
            MethodQuery(
                declaringClass = "C",
                descriptor = "()V",
                returnType = "V",
                paramTypes = listOf("I"),
                usingStrings = listOf("s"),
            )
        assertEquals("C", q.declaringClass)
        assertEquals("()V", q.descriptor)
        assertEquals("V", q.returnType)
        assertEquals(listOf("I"), q.paramTypes)
        assertEquals(listOf("s"), q.usingStrings)

        val m = MethodMatch(declaringClass = "C", obfName = "c", descriptor = "()V")
        assertEquals("C", m.declaringClass)
        assertEquals("c", m.obfName)
        assertEquals("()V", m.descriptor)
    }

    @Test
    fun `findMethod by descriptor selects the matching overload in the fake`() {
        // Exercises the FakeDexKitIndex descriptor-matching path.
        val index =
            FakeDexKitIndex(
                methods =
                    mapOf(
                        obf to
                            listOf(
                                MethodMatch(obf, "d", "(Ljava/lang/String;)V"),
                                MethodMatch(obf, "d", "(Ljava/lang/String;J)V"),
                            ),
                    ),
            )
        val match = index.findMethod(MethodQuery(declaringClass = obf, descriptor = "(Ljava/lang/String;J)V"))
        assertEquals("(Ljava/lang/String;J)V", match?.descriptor)
        assertEquals(2, index.seededMethods(obf).size)
        assertNull(index.findMethod(MethodQuery(declaringClass = "unknown")))
    }
}
