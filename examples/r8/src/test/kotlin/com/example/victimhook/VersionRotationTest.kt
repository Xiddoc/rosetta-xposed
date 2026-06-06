/*
 * Proves the core Rosetta value proposition: ONE real-name hook reference
 * survives obfuscation ROTATION between app versions.
 *
 * The victim source (TicketService.java) is obfuscated TWICE by R8:
 *   v100  →  class a.b,  method c   (seed-v100.txt / maps/100.json)
 *   v101  →  class x.y,  method q   (seed-v101.txt / maps/101.json)
 *
 * A MapRegistry holding BOTH maps is built once. For each version the test
 * picks the right map automatically (by version_code), resolves the SAME real
 * name com.example.victim.TicketService#formatTicket, and asserts the resolved
 * member name + declaring-class name match that version's obfuscated names.
 * It also invokes the method to confirm the bytecode really runs.
 *
 * No TargetPolicy allowlist is needed: the obfuscated classes are under the
 * app's own com.example.* prefix, loaded by a child URLClassLoader, so the
 * default C1 policy allows them — the same as on a device.
 */
package com.example.victimhook

import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.version.MapRegistry
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.Hooker
import io.github.xiddoc.rosetta.xposed.RosettaXposed
import io.github.xiddoc.rosetta.xposed.Unhook
import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VersionRotationTest {

    /**
     * Shared setup: load both maps and build a two-entry registry once.
     * The registry is the single object a real module would build at attach
     * time (one registry, many versions).
     */
    private val registry: MapRegistry = run {
        fun loadMap(resource: String): io.github.xiddoc.rosetta.core.model.RosettaMap {
            val json =
                javaClass.classLoader
                    .getResourceAsStream(resource)
                    ?.use { it.readBytes().decodeToString() }
                    ?: error("test resource not found: $resource")
            return MapLoader.fromJson(json)
        }
        MapRegistry.of(loadMap("maps/100.json"), loadMap("maps/101.json"))
    }

    @Test
    fun `v100 - real name resolves to a_b#c via registry`() {
        val obfJar =
            System.getProperty("rosetta.r8.obfJar")?.let(::File)
                ?: error("rosetta.r8.obfJar not set")
        require(obfJar.isFile) { "v100 obfuscated jar not found at $obfJar" }

        val appLoader = URLClassLoader(arrayOf(obfJar.toURI().toURL()), javaClass.classLoader)
        val identity = AppIdentity(packageName = "com.example.victim", versionCode = 100, versionName = "1.0.0")

        val rosetta =
            RosettaXposed.fromRegistry(registry, identity, appLoader)
                ?: error("no map for version_code 100 in registry")

        var captured: Method? = null
        rosetta
            .method("com.example.victim.TicketService", "formatTicket")
            .hook(Hooker { member ->
                captured = member as Method
                Unhook { }
            })

        val member = assertNotNull(captured, "Hooker never received a member for v100")
        assertEquals("c", member.name, "v100: expected obfuscated method name 'c'")
        assertEquals(
            "com.example.victim.a.b",
            member.declaringClass.name,
            "v100: expected obfuscated class 'com.example.victim.a.b'",
        )

        // Verify the method body runs correctly through the obfuscated bytecode.
        val instance = appLoader.loadClass("com.example.victim.a.b").getDeclaredConstructor().newInstance()
        assertEquals("ticket:v100", member.invoke(instance, "v100"))
    }

    @Test
    fun `v101 - same real name resolves to x_y#q via registry (rotation)`() {
        val obfJarV101 =
            System.getProperty("rosetta.r8.obfJarV101")?.let(::File)
                ?: error("rosetta.r8.obfJarV101 not set")
        require(obfJarV101.isFile) { "v101 obfuscated jar not found at $obfJarV101" }

        val appLoader = URLClassLoader(arrayOf(obfJarV101.toURI().toURL()), javaClass.classLoader)
        val identity = AppIdentity(packageName = "com.example.victim", versionCode = 101, versionName = "1.0.1")

        val rosetta =
            RosettaXposed.fromRegistry(registry, identity, appLoader)
                ?: error("no map for version_code 101 in registry")

        var captured: Method? = null
        rosetta
            .method("com.example.victim.TicketService", "formatTicket")
            .hook(Hooker { member ->
                captured = member as Method
                Unhook { }
            })

        val member = assertNotNull(captured, "Hooker never received a member for v101")
        assertEquals("q", member.name, "v101: expected obfuscated method name 'q'")
        assertEquals(
            "com.example.victim.x.y",
            member.declaringClass.name,
            "v101: expected obfuscated class 'com.example.victim.x.y'",
        )

        // Verify the method body runs correctly through the rotated obfuscated bytecode.
        val instance = appLoader.loadClass("com.example.victim.x.y").getDeclaredConstructor().newInstance()
        assertEquals("ticket:v101", member.invoke(instance, "v101"))
    }

    @Test
    fun `registry holds two distinct version codes and no collision`() {
        assertEquals(2, registry.size, "registry should have exactly two version codes")
        assertEquals(false, registry.hasVersionCodeCollision, "no version_code collision expected")
        assertNotNull(registry.byVersionCode(100), "v100 must be in registry")
        assertNotNull(registry.byVersionCode(101), "v101 must be in registry")
    }
}
