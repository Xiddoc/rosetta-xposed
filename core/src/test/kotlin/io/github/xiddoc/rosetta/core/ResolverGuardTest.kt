/*
 * xposed#11 — the C1 target namespace guard is enforced at the :core Resolver
 * chokepoint, so a standalone :core consumer (no :xposed binding) cannot bypass
 * it. Mirrors the Frida resolver, which calls assertTargetAllowed on every
 * resolve path.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.FieldEntry
import io.github.xiddoc.rosetta.core.model.MethodEntry
import io.github.xiddoc.rosetta.core.model.MethodOverloads
import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.core.policy.TargetPolicy
import io.github.xiddoc.rosetta.core.resolver.Resolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResolverGuardTest {
    private fun mapWith(
        obf: String,
        app: String = "com.example.app",
    ): RosettaMap =
        RosettaMap(
            schemaVersion = 2,
            app = app,
            version = "1.0.0",
            versionCode = 100,
            classes =
                mapOf(
                    "com.example.RealClient" to
                        ClassEntry(
                            obfuscated = obf,
                            methods = mapOf("exec" to MethodOverloads(listOf(MethodEntry(obfuscated = "e", signature = "()V")))),
                            fields = mapOf("id" to FieldEntry(obfuscated = "f", type = "I")),
                        ),
                ),
        )

    @Test
    fun `resolveClass denies a reserved framework target through core`() {
        val resolver = Resolver(mapWith("java.lang.Runtime"))
        val ex = assertFailsWith<TargetPolicyException> { resolver.resolveClass("com.example.RealClient") }
        assertEquals("java.lang.Runtime", ex.target)
        assertEquals("com.example.RealClient", ex.name)
    }

    @Test
    fun `resolveMethod denies a reserved framework target through core`() {
        // The method path resolves the owning class first, so the guard fires
        // on the class target before any method lookup.
        val resolver = Resolver(mapWith("android.app.ActivityManager"))
        assertFailsWith<TargetPolicyException> { resolver.resolveMethod("com.example.RealClient", "exec") }
    }

    @Test
    fun `resolveField denies a reserved framework target through core`() {
        val resolver = Resolver(mapWith("dalvik.system.DexClassLoader"))
        assertFailsWith<TargetPolicyException> { resolver.resolveField("com.example.RealClient", "id") }
    }

    @Test
    fun `a denied target is never cached`() {
        val resolver = Resolver(mapWith("java.lang.Runtime"))
        // Two attempts both throw — the first must not have poisoned the cache
        // with a half-built value (the guard runs BEFORE the cache put, so a
        // denied target throws and stores nothing).
        assertFailsWith<TargetPolicyException> { resolver.resolveClass("com.example.RealClient") }
        assertFailsWith<TargetPolicyException> { resolver.resolveClass("com.example.RealClient") }
    }

    @Test
    fun `app-prefixed and package-local targets are allowed through core`() {
        // Package-local obf (no dot) and app-prefixed obf both pass.
        assertEquals("a", Resolver(mapWith("a")).resolveClass("com.example.RealClient").obfName)
        assertEquals(
            "com.example.app.Impl",
            Resolver(mapWith("com.example.app.Impl")).resolveClass("com.example.RealClient").obfName,
        )
    }

    @Test
    fun `an allowlisted reserved target is permitted via policy`() {
        val resolver = Resolver(mapWith("java.lang.Runtime"), TargetPolicy(allow = listOf("java.lang.Runtime")))
        assertEquals("java.lang.Runtime", resolver.resolveClass("com.example.RealClient").obfName)
    }

    @Test
    fun `translateType denies a mapped type that resolves to a reserved target`() {
        // Parity with the Frida twin (resolver.ts:438,443): the arg-type → obf
        // path also produces a map-controlled target FQN, so a mapped type whose
        // obf lands in a denied namespace must throw, not silently return it.
        val resolver = Resolver(mapWith("java.lang.Runtime"))
        val ex = assertFailsWith<TargetPolicyException> { resolver.translateType("com.example.RealClient") }
        assertEquals("java.lang.Runtime", ex.target)
        assertEquals("com.example.RealClient", ex.name)
    }

    @Test
    fun `translateType denies a denied target reached via a runtime override`() {
        // The override branch is guarded too (mirrors Frida's override branch).
        val resolver = Resolver(mapWith("a"))
        resolver.override(
            io.github.xiddoc.rosetta.core.resolver.DiscoveredClass(
                realName = "com.example.RealClient",
                obfName = "android.app.ActivityManager",
            ),
        )
        assertFailsWith<TargetPolicyException> { resolver.translateType("com.example.RealClient") }
    }

    @Test
    fun `translateType allows a mapped app-prefixed type and passes unmapped types through`() {
        val resolver = Resolver(mapWith("com.example.app.Impl"))
        // Mapped, app-prefixed obf is allowed and translated.
        assertEquals("com.example.app.Impl", resolver.translateType("com.example.RealClient"))
        // An unmapped type is the caller's OWN input (not a map-controlled
        // target) and passes through untouched — not subject to the guard.
        assertEquals("java.lang.String", resolver.translateType("java.lang.String"))
    }

    @Test
    fun `an explicit appPackage overrides the prefix derived from the map app`() {
        // Map app is com.other, but we tell the resolver the real app package is
        // com.example, so a com.example.* obf target is app-prefixed (allowed).
        val resolver =
            Resolver(
                mapWith("com.example.app.Impl", app = "com.other.thing"),
                TargetPolicy(),
                appPackage = "com.example.app",
            )
        assertEquals("com.example.app.Impl", resolver.resolveClass("com.example.RealClient").obfName)
    }
}
