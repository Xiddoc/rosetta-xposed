/*
 * Coverage-completing tests for the layer-4 binding.
 *
 * Where RosettaXposedTest drives the happy bind path, this fills the
 * remaining branches: the JVM-descriptor table for every primitive/array/
 * object type, the constructor-binding and not-found failure modes on
 * MethodTarget/FieldTarget, the registry-selection success + null paths, the
 * plain value/exception types (AppIdentity, BindException), and the
 * architected-but-unbuilt backends that throw on purpose (DeferredBinding,
 * DynamicResolutionBackend). Together with RosettaXposedTest they bring the
 * module to 100% line + branch coverage (enforced by koverVerify).
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.version.MapRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverageTest {
    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"

    private val map =
        MapLoader.fromJson(
            """
            {
              "schema_version": 5,
              "app": "com.example.app",
              "version": "1.0.0",
              "version_code": 100,
              "classes": {
                "com.example.RealClient": {
                  "obfuscated": "$obf",
                  "methods": {
                    "single": { "obfuscated": "c", "signature": "(Ljava/lang/String;)Ljava/lang/String;" },
                    "ctor": { "obfuscated": "<init>", "signature": "(Ljava/lang/String;)V" },
                    "flagCtor": { "obfuscated": "irrelevant", "signature": "(Ljava/lang/String;)V", "is_constructor": true },
                    "ghostCtor": { "obfuscated": "<init>", "signature": "(J)V" },
                    "ghost": { "obfuscated": "noSuchMethod", "signature": "()V" }
                  },
                  "fields": {
                    "id": { "obfuscated": "a", "type": "Ljava/lang/String;" },
                    "ghostField": { "obfuscated": "noSuchField", "type": "I" }
                  }
                }
              }
            }
            """.trimIndent(),
        )

    // The fixture obf class is outside the app namespace; allowlist it (the
    // guard itself is covered in TargetGuardTest / RosettaXposedTest).
    private val rosetta =
        RosettaXposed.fromMapUnverified(map, javaClass.classLoader, TargetPolicy(allow = listOf(obf)))

    // ---- JvmDescriptors: every primitive, plus array and object wrapping.

    @Test
    fun `JvmDescriptors maps every primitive, arrays, and objects`() {
        assertEquals("V", JvmDescriptors.of(Void.TYPE))
        assertEquals("Z", JvmDescriptors.of(Boolean::class.javaPrimitiveType!!))
        assertEquals("B", JvmDescriptors.of(Byte::class.javaPrimitiveType!!))
        assertEquals("C", JvmDescriptors.of(Char::class.javaPrimitiveType!!))
        assertEquals("S", JvmDescriptors.of(Short::class.javaPrimitiveType!!))
        assertEquals("I", JvmDescriptors.of(Int::class.javaPrimitiveType!!))
        assertEquals("J", JvmDescriptors.of(Long::class.javaPrimitiveType!!))
        assertEquals("F", JvmDescriptors.of(Float::class.javaPrimitiveType!!))
        assertEquals("D", JvmDescriptors.of(Double::class.javaPrimitiveType!!))
        assertEquals("[I", JvmDescriptors.of(IntArray::class.java))
        assertEquals("Ljava/lang/String;", JvmDescriptors.of(String::class.java))
        assertEquals(
            listOf("Ljava/lang/String;", "I"),
            JvmDescriptors.paramsOf(arrayOf(String::class.java, Int::class.javaPrimitiveType!!)),
        )
    }

    // ---- MethodTarget: constructor binding + the not-found failure modes.
    //
    // xposed#14 L3 — constructor dispatch has TWO entry branches, each pinned by
    // its own test below:
    //   1. the `<init>` MAGIC-STRING branch (obfuscated name == "<init>"):
    //      `binds a constructor target to a declared constructor` (via `ctor`).
    //   2. the `is_constructor == true` FLAG branch (any obfuscated name):
    //      `the is_constructor flag drives constructor dispatch ...` (via
    //      `flagCtor`, whose obf name is deliberately NOT "<init>").

    @Test
    fun `binds a constructor target to a declared constructor`() {
        // L3 branch 1: `ctor`'s obfuscated name IS the "<init>" magic string, so
        // the magic-string arm (not the is_constructor flag) routes it to a ctor.
        val member = rosetta.method("com.example.RealClient", "ctor").member()
        assertTrue(member is java.lang.reflect.Constructor<*>)
        assertEquals(1, (member as java.lang.reflect.Constructor<*>).parameterCount)
    }

    @Test
    fun `the is_constructor flag drives constructor dispatch even when obf name is not init`() {
        // L3 branch 2: `flagCtor` carries is_constructor=true but an obfuscated
        // name of "irrelevant" (NOT "<init>"). The flag — not the magic string —
        // must route it through the constructor branch, binding the (String) ctor.
        val member = rosetta.method("com.example.RealClient", "flagCtor").member()
        assertTrue(member is java.lang.reflect.Constructor<*>)
        assertEquals(1, (member as java.lang.reflect.Constructor<*>).parameterCount)
    }

    @Test
    fun `a constructor whose signature matches nothing throws BindException`() {
        // `<init>` resolved with a (J)V signature: no such constructor exists.
        assertFailsWith<BindException> { rosetta.method("com.example.RealClient", "ghostCtor").member() }
    }

    @Test
    fun `a method whose obf name matches nothing throws BindException`() {
        assertFailsWith<BindException> { rosetta.method("com.example.RealClient", "ghost").member() }
    }

    // ---- FieldTarget: the not-found failure mode.

    @Test
    fun `a field whose obf name matches nothing throws BindException`() {
        assertFailsWith<BindException> { rosetta.field("com.example.RealClient", "ghostField").field() }
    }

    // ---- RosettaXposed.fromRegistry: success + null selection paths.

    @Test
    fun `fromRegistry selects by version_code and binds`() {
        val registry: MapRegistry = MapRegistry.of(map)
        val identity = AppIdentity(packageName = "com.example.app", versionCode = 100, versionName = "1.0.0")
        val bound = RosettaXposed.fromRegistry(registry, identity, javaClass.classLoader)
        assertNotNull(bound)
        assertTrue(bound.knows("com.example.RealClient"))
    }

    @Test
    fun `fromRegistry returns null when no map matches`() {
        val registry: MapRegistry = MapRegistry.of(map)
        val identity = AppIdentity(packageName = "com.example.app", versionCode = 999)
        assertNull(RosettaXposed.fromRegistry(registry, identity, javaClass.classLoader))
    }

    // ---- Plain value + exception types.

    @Test
    fun `AppIdentity carries all of its fields`() {
        val id =
            AppIdentity(
                packageName = "com.example.app",
                versionCode = 42,
                versionName = "1.2.3",
                signerSha256s = setOf("a".repeat(64), "b".repeat(64)),
            )
        assertEquals("com.example.app", id.packageName)
        assertEquals(42, id.versionCode)
        assertEquals("1.2.3", id.versionName)
        assertEquals(setOf("a".repeat(64), "b".repeat(64)), id.signerSha256s)
    }

    @Test
    fun `BindException carries message and optional cause`() {
        val cause = IllegalStateException("boom")
        val ex = BindException("wrapped", cause)
        assertEquals("wrapped", ex.message)
        assertEquals(cause, ex.cause)
        // The no-cause constructor path.
        assertNull(BindException("just a message").cause)
    }

    // DeferredBinding is now built (B.2); its behaviour — guarded probe,
    // run-once, both watchers, the denied-target M1 case — is covered in
    // DeferredBindingTest.

    // The dynamic backend is now built (B.1); its behaviour is covered in
    // DynamicResolutionBackendTest. See that file for the strategy + miss +
    // feedback + provenance + ReDoS-bounds cases.

    // ---- Internal RosettaXposed constructor with the default policy.

    @Test
    fun `internal constructor uses the default policy`() {
        // Exercise the internal (backend, classLoader, appName) constructor with
        // the default-valued policy argument; an app-prefixed but unknown class
        // passes the guard and surfaces as a BindException, not a policy denial.
        // (Public unverified construction is only via fromMapUnverified.)
        val r = RosettaXposed(StaticResolutionBackend(map), javaClass.classLoader, "com.example.app")
        assertTrue(r.knows("com.example.RealClient"))
    }

    // ---- The ResolutionBackend default-argument bridge (DefaultImpls).

    @Test
    fun `resolveMethod through the interface uses the argTypes default`() {
        // Calling the two-arg overload via a ResolutionBackend reference goes
        // through the interface's default-argument bridge.
        // The fixture obf is outside the app namespace; allowlist it so the
        // :core C1 guard (xposed#11) permits the resolve through this backend.
        val backend: ResolutionBackend = StaticResolutionBackend(map, TargetPolicy(allow = listOf(obf)))
        val resolved = backend.resolveMethod("com.example.RealClient", "single")
        assertEquals("c", resolved.obfName)
    }

    @Test
    fun `translateType maps a known real class to obf and passes others through`() {
        // The translator the dynamic discovery backend borrows for real-name
        // argTypes (see RosettaXposed.fromMapWithDiscovery): a mapped real name
        // → its obf short name; an unmapped framework type passes through.
        // translateType's MAPPED output is now C1-guarded (xposed#13 parity with
        // Frida resolver.ts:438,443), so the out-of-namespace fixture obf must be
        // allowlisted — exactly as the resolveMethod backend test above does.
        val backend = StaticResolutionBackend(map, TargetPolicy(allow = listOf(obf)))
        assertEquals(obf, backend.translateType("com.example.RealClient"))
        assertEquals("java.lang.String", backend.translateType("java.lang.String"))
    }
}
