/*
 * Binding-layer tests. These run on a plain JVM: the "app" classes are the
 * fixture classes on the test classpath, so we exercise real reflection
 * member-matching, overload disambiguation, the Hooker seam, and the bind
 * failure modes — no Android SDK or device required.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.TargetPolicyException
import io.github.xiddoc.rosetta.core.version.MapRegistry
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
              "schema_version": 4,
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
              "schema_version": 4,
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
        val registry = MapRegistry.of(map)
        val id = AppIdentity(packageName = "com.example.app", versionCode = 100, versionName = "1.0.0")
        val bound = RosettaXposed.fromRegistry(registry, id, javaClass.classLoader, policy)
        assertNotNull(bound)
        assertEquals(obf, bound.useClass("com.example.RealClient").load().name)
    }

    // ---- xposed#12: inherited (superclass) member binding --------------------

    private val childObf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfChild"
    private val parentObf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfParent"

    // `RemoteServiceClient extends AbstractServiceClient`: the hooked method `e`
    // and field `p` are declared on the PARENT (ObfParent); the obfuscated class
    // the map points at is the CHILD (ObfChild). The `extends` edge is recorded.
    private val inheritanceMap =
        MapLoader.fromJson(
            """
            {
              "schema_version": 4,
              "app": "com.example.app",
              "version": "1.0.0",
              "version_code": 100,
              "classes": {
                "com.example.RemoteServiceClient": {
                  "obfuscated": "$childObf",
                  "extends": "$parentObf",
                  "methods": {
                    "inherited": { "obfuscated": "e", "signature": "(Ljava/lang/String;)Ljava/lang/String;" },
                    "own": { "obfuscated": "f", "signature": "(J)J" }
                  },
                  "fields": { "inheritedField": { "obfuscated": "p", "type": "Ljava/lang/String;" } }
                }
              }
            }
            """.trimIndent(),
        )

    private val inheritancePolicy = TargetPolicy(allow = listOf(childObf, parentObf))

    private val inherited =
        RosettaXposed.fromMapUnverified(inheritanceMap, javaClass.classLoader, inheritancePolicy)

    @Test
    fun `binds a method declared on a parent class`() {
        val member =
            inherited.method("com.example.RemoteServiceClient", "inherited").member()
                as java.lang.reflect.Method
        assertEquals("e", member.name)
        // The method is genuinely declared on the PARENT, not the child.
        assertEquals(parentObf, member.declaringClass.name)
    }

    @Test
    fun `still binds a method declared on the child itself`() {
        val member =
            inherited.method("com.example.RemoteServiceClient", "own").member()
                as java.lang.reflect.Method
        assertEquals("f", member.name)
        assertEquals(childObf, member.declaringClass.name)
    }

    @Test
    fun `binds a field declared on a parent class`() {
        val field = inherited.field("com.example.RemoteServiceClient", "inheritedField").field()
        assertEquals("p", field.name)
        assertEquals(parentObf, field.declaringClass.name)
    }

    @Test
    fun `a method absent on the whole chain still throws BindException`() {
        val absentMap =
            MapLoader.fromJson(
                """
                {
                  "schema_version": 4,
                  "app": "com.example.app",
                  "version": "1.0.0",
                  "version_code": 100,
                  "classes": {
                    "com.example.RemoteServiceClient": {
                      "obfuscated": "$childObf",
                      "methods": { "ghost": { "obfuscated": "noSuchMethod", "signature": "()V" } }
                    }
                  }
                }
                """.trimIndent(),
            )
        val r = RosettaXposed.fromMapUnverified(absentMap, javaClass.classLoader, inheritancePolicy)
        assertFailsWith<BindException> { r.method("com.example.RemoteServiceClient", "ghost").member() }
    }

    // ---- xposed#12 SECURITY: the walk must not escape the C1 namespace ----

    private val frameworkChildObf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfFrameworkChild"

    // The map points at obf members (`nextInt`/`()I`, field `seed`) that exist
    // ONLY on a DENIED framework ancestor (`java.util.Random`), not on the
    // allowlisted child. Only the CHILD is allowlisted; the parent is NOT — so
    // the walk must STOP at the framework parent and never bind its member.
    private val escapeMap =
        MapLoader.fromJson(
            """
            {
              "schema_version": 4,
              "app": "com.example.app",
              "version": "1.0.0",
              "version_code": 100,
              "classes": {
                "com.example.FrameworkChild": {
                  "obfuscated": "$frameworkChildObf",
                  "methods": { "nextInt": { "obfuscated": "nextInt", "signature": "()I" } },
                  "fields": { "seed": { "obfuscated": "seed", "type": "Ljava/util/concurrent/atomic/AtomicLong;" } }
                }
              }
            }
            """.trimIndent(),
        )

    // ONLY the child is allowlisted — the framework parent is deliberately not.
    private val escapePolicy = TargetPolicy(allow = listOf(frameworkChildObf))
    private val escaper = RosettaXposed.fromMapUnverified(escapeMap, javaClass.classLoader, escapePolicy)

    @Test
    fun `inherited-member walk does NOT bind a member on a denied framework parent`() {
        // `nextInt()I` is declared on a denied java-namespace ancestor, reachable
        // only by walking past the allowlisted child. The C1 walk gate must stop
        // there, so binding fails rather than setAccessible-ing a framework member.
        assertFailsWith<BindException> {
            escaper.method("com.example.FrameworkChild", "nextInt").member()
        }
    }

    @Test
    fun `inherited-field walk does NOT bind a field on a denied framework parent`() {
        // `seed` is declared on `java.util.Random` (a denied ancestor).
        assertFailsWith<BindException> {
            escaper.field("com.example.FrameworkChild", "seed").field()
        }
    }

    @Test
    fun `walk stops at an app-prefixed but platform-loaded parent (loader-check branch)`() {
        // Isolate the WALK gate's defense-in-depth loader check (the branch the
        // namespace rule alone can't reach): app-prefix the fixtures to
        // `io.github` so BOTH child and parent pass the NAMESPACE gate, but
        // allowlist ONLY the child. The child then loads (allowlisted), but the
        // parent — app-prefixed, NOT allowlisted, and realised by the test JVM's
        // SYSTEM loader — fails the walk's loader check, so the walk stops there
        // and the parent-declared method `e` is never bound.
        val map =
            MapLoader.fromJson(
                """
                {
                  "schema_version": 4,
                  "app": "io.github.app",
                  "version": "1.0.0",
                  "version_code": 100,
                  "classes": {
                    "com.example.RemoteServiceClient": {
                      "obfuscated": "$childObf",
                      "extends": "$parentObf",
                      "methods": { "inherited": { "obfuscated": "e", "signature": "(Ljava/lang/String;)Ljava/lang/String;" } }
                    }
                  }
                }
                """.trimIndent(),
            )
        // Only the child is allowlisted; the parent is app-prefixed (passes the
        // namespace gate) but hits the system-loaded loader-check deny.
        val r = RosettaXposed.fromMapUnverified(map, javaClass.classLoader, TargetPolicy(allow = listOf(childObf)))
        assertFailsWith<BindException> { r.method("com.example.RemoteServiceClient", "inherited").member() }
    }

    @Test
    fun `walk searches an app-prefixed parent realised by a non-platform loader`() {
        // The complement of the loader-check test: when the parent is
        // app-prefixed AND realised by a NON-platform (app-style) loader, the
        // walk gate must SEARCH it (the normal app-hierarchy case) and bind the
        // inherited member — proving the gate does not over-deny ordinary app
        // superclasses. A defining loader gives child+parent a non-system
        // defining loader; app-prefix `io.github` passes the namespace gate with
        // NO allowlist, so the search relies purely on the loader check.
        val defining =
            io.github.xiddoc.rosetta.xposed.fixtures.LocalDefiningClassLoader(
                javaClass.classLoader,
                setOf(childObf, parentObf),
            )
        val map =
            MapLoader.fromJson(
                """
                {
                  "schema_version": 4,
                  "app": "io.github.app",
                  "version": "1.0.0",
                  "version_code": 100,
                  "classes": {
                    "com.example.RemoteServiceClient": {
                      "obfuscated": "$childObf",
                      "extends": "$parentObf",
                      "methods": { "inherited": { "obfuscated": "e", "signature": "(Ljava/lang/String;)Ljava/lang/String;" } }
                    }
                  }
                }
                """.trimIndent(),
            )
        // No allowlist: the app-prefix rule passes the namespace gate, and the
        // non-platform defining loader passes the loader check, so the parent is
        // searchable and the inherited method binds.
        val r = RosettaXposed.fromMapUnverified(map, defining, TargetPolicy())
        val member =
            r.method("com.example.RemoteServiceClient", "inherited").member()
                as java.lang.reflect.Method
        assertEquals("e", member.name)
        assertEquals(parentObf, member.declaringClass.name)
    }
}
