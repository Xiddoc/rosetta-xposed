/*
 * The WITH-R8 counterpart to the harness Walkthrough: identical Rosetta flow,
 * but the victim class is loaded from a jar that REAL R8 obfuscated at build
 * time (see build.gradle.kts `obfuscate`). If R8 ever emitted names other than
 * what maps/100.json declares, `member()` would throw and this test would fail
 * — so it doubles as a guard that the committed map matches real obfuscator
 * output.
 *
 * Note there is NO TargetPolicy allowlist here (unlike the harness): the
 * obfuscated victim is realised by a child URLClassLoader under the app's own
 * `com.example.*` prefix, exactly like on a device, so the C1 default policy
 * allows it without an escape hatch.
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

class R8WalkthroughTest {
    @Test
    fun `resolves real names against real R8-obfuscated bytecode`() {
        val obfJar =
            System.getProperty("rosetta.r8.obfJar")?.let(::File)
                ?: error("rosetta.r8.obfJar system property not set (the obfuscate task should set it)")
        require(obfJar.isFile) { "obfuscated jar not found at $obfJar — did the obfuscate task run?" }

        // Load the obfuscated victim like the app would: a child loader under
        // the test loader (NOT the system/platform loader).
        val appLoader = URLClassLoader(arrayOf(obfJar.toURI().toURL()), javaClass.classLoader)

        val json =
            javaClass.classLoader
                .getResourceAsStream("maps/100.json")!!
                .use { it.readBytes().decodeToString() }
        val map = MapLoader.fromJson(json)
        val registry = MapRegistry.of(map)
        val identity = AppIdentity(packageName = "com.example.victim", versionCode = 100, versionName = "1.0.0")

        // No allowlist policy needed — the obf class is under the app prefix.
        val rosetta =
            RosettaXposed.fromRegistry(registry, identity, appLoader)
                ?: error("no binding for version_code ${identity.versionCode}")

        var captured: Method? = null
        rosetta
            .method("com.example.victim.TicketService", "formatTicket")
            .hook(
                Hooker { member ->
                    captured = member as Method
                    Unhook { }
                },
            )

        val member = assertNotNull(captured, "the Hooker never received a member")
        // R8 really renamed formatTicket -> c.
        assertEquals("c", member.name)
        assertEquals("com.example.victim.a.b", member.declaringClass.name)

        // Invoke the obfuscated method on a fresh instance to prove it runs.
        val instance = appLoader.loadClass("com.example.victim.a.b").getDeclaredConstructor().newInstance()
        assertEquals("ticket:R8", member.invoke(instance, "R8"))
    }
}
