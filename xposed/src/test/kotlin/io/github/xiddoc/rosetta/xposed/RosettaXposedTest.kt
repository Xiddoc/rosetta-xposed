/*
 * Binding-layer tests. These run on a plain JVM: the "app" classes are the
 * fixture classes on the test classpath, so we exercise real reflection
 * member-matching, overload disambiguation, the Hooker seam, and the bind
 * failure modes — no Android SDK or device required.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MapLoader
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
                "com.example.Missing": { "obfuscated": "no.such.ObfClass" }
              }
            }
            """.trimIndent(),
        )

    private val rosetta = RosettaXposed.fromMap(map, javaClass.classLoader)

    @Test
    fun `binds a single-overload method to the obfuscated member`() {
        val member = rosetta.method("com.example.RealClient", "single").member()
        assertEquals("c", member.name)
        assertEquals(1, (member as java.lang.reflect.Method).parameterCount)
    }

    @Test
    fun `disambiguates an overload by arg types`() {
        val twoArg =
            rosetta.method("com.example.RealClient", "over", listOf("java.lang.String", "long"))
                .member() as java.lang.reflect.Method
        assertEquals("d", twoArg.name)
        assertEquals(2, twoArg.parameterCount)

        val oneArg =
            rosetta.method("com.example.RealClient", "over", listOf("java.lang.String"))
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
}
