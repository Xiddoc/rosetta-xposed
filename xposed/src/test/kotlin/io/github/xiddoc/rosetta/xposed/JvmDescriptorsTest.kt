/*
 * JvmDescriptors round-trip tests.
 *
 * The reflection bridge (`JvmDescriptors`, which renders reflected types to JVM
 * descriptors) and the neutral resolver (`parseSignatureArgs`, which splits a
 * signature back into descriptors) must agree on one descriptor vocabulary —
 * both now delegate to the shared `:core` Descriptors util. These tests pin
 * that agreement on a fixed class so the two sides can never silently fork.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.resolver.Descriptors
import io.github.xiddoc.rosetta.core.resolver.parseSignatureArgs
import io.github.xiddoc.rosetta.xposed.fixtures.ObfClient
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmDescriptorsTest {
    @Test
    fun `paramsOf round-trips through parseSignatureArgs on a fixed class`() {
        // The two-arg overload d(String, long) exercises an object + primitive.
        val method =
            ObfClient::class.java.declaredMethods.single { it.name == "d" && it.parameterTypes.size == 2 }
        val rendered = JvmDescriptors.paramsOf(method.parameterTypes)
        assertEquals(listOf("Ljava/lang/String;", "J"), rendered)

        // Re-assemble a signature and parse it back: the bridge's output is a
        // valid input to the resolver's splitter, and the two agree.
        val signature = "(" + rendered.joinToString("") + ")V"
        assertEquals(rendered, parseSignatureArgs(signature))
    }

    @Test
    fun `JvmDescriptors uses the shared core primitive vocabulary`() {
        // Every primitive Class<*> renders to the same letter the core table
        // declares, proving the bridge delegates rather than re-declaring.
        val cases =
            mapOf(
                Boolean::class.javaPrimitiveType!! to "boolean",
                Byte::class.javaPrimitiveType!! to "byte",
                Char::class.javaPrimitiveType!! to "char",
                Short::class.javaPrimitiveType!! to "short",
                Int::class.javaPrimitiveType!! to "int",
                Long::class.javaPrimitiveType!! to "long",
                Float::class.javaPrimitiveType!! to "float",
                Double::class.javaPrimitiveType!! to "double",
                Void.TYPE to "void",
            )
        for ((cls, name) in cases) {
            assertEquals(Descriptors.primitive(name), JvmDescriptors.of(cls))
        }
    }

    @Test
    fun `JvmDescriptors renders arrays and objects via the shared util`() {
        assertEquals("[I", JvmDescriptors.of(IntArray::class.java))
        assertEquals("[Ljava/lang/String;", JvmDescriptors.of(Array<String>::class.java))
        assertEquals(Descriptors.objectDescriptor("java.lang.String"), JvmDescriptors.of(String::class.java))
    }
}
