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
              "schema_version": 2,
              "app": "com.example.app",
              "version": "1.0.0",
              "version_code": 100,
              "classes": {
                "com.example.RealClient": {
                  "obfuscated": "$obf",
                  "methods": {
                    "single": { "obfuscated": "c", "signature": "(Ljava/lang/String;)Ljava/lang/String;" },
                    "ctor": { "obfuscated": "<init>", "signature": "(Ljava/lang/String;)V" },
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

    private val rosetta = RosettaXposed.fromMap(map, javaClass.classLoader)

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

    @Test
    fun `binds a constructor target to a declared constructor`() {
        val member = rosetta.method("com.example.RealClient", "ctor").member()
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
        val registry: MapRegistry = mapOf("1.0.0" to map)
        val identity = AppIdentity(packageName = "com.example.app", versionCode = 100, versionName = "1.0.0")
        val bound = RosettaXposed.fromRegistry(registry, identity, javaClass.classLoader)
        assertNotNull(bound)
        assertTrue(bound.knows("com.example.RealClient"))
    }

    @Test
    fun `fromRegistry returns null when no map matches`() {
        val registry: MapRegistry = mapOf("1.0.0" to map)
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
                signerSha256 = "deadbeef",
            )
        assertEquals("com.example.app", id.packageName)
        assertEquals(42, id.versionCode)
        assertEquals("1.2.3", id.versionName)
        assertEquals("deadbeef", id.signerSha256)
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

    // ---- Architected-but-unbuilt backends (throw on purpose, RFC 0001 D2).

    @Test
    fun `DeferredBinding whenClassAvailable is not yet implemented`() {
        assertFailsWith<NotImplementedError> {
            DeferredBinding.whenClassAvailable("com.example.RealClient") { /* never called */ }
        }
    }

    @Test
    fun `DynamicResolutionBackend never resolves and every entry point throws`() {
        val backend = DynamicResolutionBackend()
        assertTrue(!backend.canResolve("com.example.RealClient"))
        assertFailsWith<NotImplementedError> { backend.resolveClass("com.example.RealClient") }
        assertFailsWith<NotImplementedError> { backend.resolveMethod("com.example.RealClient", "single") }
        assertFailsWith<NotImplementedError> { backend.resolveField("com.example.RealClient", "id") }
    }

    // ---- The ResolutionBackend default-argument bridge (DefaultImpls).

    @Test
    fun `resolveMethod through the interface uses the argTypes default`() {
        // Calling the two-arg overload via a ResolutionBackend reference goes
        // through the interface's default-argument bridge.
        val backend: ResolutionBackend = StaticResolutionBackend(map)
        val resolved = backend.resolveMethod("com.example.RealClient", "single")
        assertEquals("c", resolved.obfName)
    }
}
