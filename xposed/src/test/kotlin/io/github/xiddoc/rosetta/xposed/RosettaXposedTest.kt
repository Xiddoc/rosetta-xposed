/*
 * Binding-layer tests. These run on a plain JVM: the "app" classes are the
 * fixture classes on the test classpath, so we exercise real reflection
 * member-matching, overload disambiguation, the Hooker seam, and the bind
 * failure modes — no Android SDK or device required.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.TargetPolicyException
import java.lang.reflect.Member
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RosettaXposedTest {
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
                    "over": [
                      { "obfuscated": "d", "signature": "(Ljava/lang/String;)V" },
                      { "obfuscated": "d", "signature": "(Ljava/lang/String;J)V" }
                    ]
                  },
                  "fields": { "id": { "obfuscated": "a", "type": "Ljava/lang/String;" } }
                },
                "com.example.Missing": { "obfuscated": "com.example.app.NoSuchObfClass" }
              }
            }
            """.trimIndent(),
        )

    // The fixture obf class lives under `io.github.xiddoc...`, outside the
    // `com.example` app namespace, so it is allowed via the escape-hatch
    // allowlist (the namespace guard is exercised directly in TargetGuardTest
    // and at the plumbing level below).
    private val policy = TargetPolicy(allow = listOf(obf))

    private val rosetta = RosettaXposed.fromMapUnverified(map, javaClass.classLoader, policy)

    @Test
    fun `binds a single-overload method to the obfuscated member`() {
        val member = rosetta.method("com.example.RealClient", "single").member()
        assertEquals("c", member.name)
        assertEquals(1, (member as java.lang.reflect.Method).parameterCount)
    }

    @Test
    fun `disambiguates an overload by arg types`() {
        val twoArg =
            rosetta
                .method("com.example.RealClient", "over", listOf("java.lang.String", "long"))
                .member() as java.lang.reflect.Method
        assertEquals("d", twoArg.name)
        assertEquals(2, twoArg.parameterCount)

        val oneArg =
            rosetta
                .method("com.example.RealClient", "over", listOf("java.lang.String"))
                .member() as java.lang.reflect.Method
        assertEquals(1, oneArg.parameterCount)
    }

    @Test
    fun `hooker receives the resolved member`() {
        var captured: Member? = null
        val handle =
            rosetta.method("com.example.RealClient", "single").hook { m ->
                captured = m
                Unhook { /* no-op */ }
            }
        assertNotNull(captured)
        assertEquals("c", captured!!.name)
        assertNotNull(handle)
    }

    @Test
    fun `binds a field to the obfuscated member`() {
        val field = rosetta.field("com.example.RealClient", "id").field()
        assertEquals("a", field.name)
    }

    @Test
    fun `useClass loads the obfuscated class`() {
        val cls = rosetta.useClass("com.example.RealClient").load()
        assertEquals(obf, cls.name)
    }

    @Test
    fun `knows reflects the loaded map`() {
        assertTrue(rosetta.knows("com.example.RealClient"))
        assertTrue(!rosetta.knows("com.example.Unknown"))
    }

    @Test
    fun `not-yet-loadable class throws BindException`() {
        assertFailsWith<BindException> { rosetta.useClass("com.example.Missing").load() }
    }

    // ---- C1: target namespace guard plumbing ---------------------------------

    private val maliciousMap =
        MapLoader.fromJson(
            """
            {
              "schema_version": 2,
              "app": "com.example.app",
              "version": "1.0.0",
              "version_code": 100,
              "classes": {
                "com.example.RealClient": {
                  "obfuscated": "java.lang.Runtime",
                  "methods": { "exec": { "obfuscated": "exec", "signature": "(Ljava/lang/String;)V" } },
                  "fields": {}
                }
              }
            }
            """.trimIndent(),
        )

    @Test
    fun `default policy rejects a framework target before any load`() {
        val r = RosettaXposed.fromMapUnverified(maliciousMap, javaClass.classLoader)
        val ex =
            assertFailsWith<TargetPolicyException> { r.useClass("com.example.RealClient").load() }
        assertEquals("java.lang.Runtime", ex.target)
        assertEquals("com.example.RealClient", ex.name)
    }

    @Test
    fun `malicious map throws BEFORE Class-forName and setAccessible`() {
        // A class that fails its static initializer would throw on
        // Class.forName(..., true, ...); the guard must reject before we ever
        // initialize OR setAccessible. We assert the guard exception type,
        // which can only be produced before any load happens.
        val r = RosettaXposed.fromMapUnverified(maliciousMap, javaClass.classLoader)
        assertFailsWith<TargetPolicyException> { r.method("com.example.RealClient", "exec").member() }
        assertFailsWith<TargetPolicyException> { r.useClass("com.example.RealClient").load() }
    }

    @Test
    fun `policy plumbs through fromMap`() {
        val id = AppIdentity(packageName = "com.example.app", versionCode = 100)
        val bound = RosettaXposed.fromMap(map, javaClass.classLoader, id, policy)
        assertEquals(obf, bound.useClass("com.example.RealClient").load().name)
        // Without the allowlist policy, fromMap denies the fixture target.
        val denied = RosettaXposed.fromMap(map, javaClass.classLoader, id)
        assertFailsWith<TargetPolicyException> { denied.useClass("com.example.RealClient").load() }
    }

    @Test
    fun `policy plumbs through fromRegistry`() {
        val registry = mapOf("1.0.0" to map)
        val id = AppIdentity(packageName = "com.example.app", versionCode = 100, versionName = "1.0.0")
        val bound = RosettaXposed.fromRegistry(registry, id, javaClass.classLoader, policy)
        assertNotNull(bound)
        assertEquals(obf, bound.useClass("com.example.RealClient").load().name)
    }
}
