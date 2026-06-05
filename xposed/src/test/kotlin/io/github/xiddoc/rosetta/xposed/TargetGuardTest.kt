/*
 * Unit tests for the C1 target namespace guard (RFC 0001). These drive the
 * pure decision core ([TargetGuard]) in isolation — no map, no backend — plus
 * the [TargetPolicy] data class and the defense-in-depth loader check via the
 * binding chokepoint. The cross-client `target-policy` parity fixtures land in
 * a later conformance task; these are the Kotlin-side focused unit tests.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.TargetPolicyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetGuardTest {
    private val policy = TargetPolicy()
    private val appPrefix = TargetGuard.appPrefixOf("com.example.app", policy)

    private fun allowed(fqn: String): Boolean = TargetGuard.isAllowed(fqn, appPrefix, policy)

    // ---- app-prefix derivation ----------------------------------------------

    @Test
    fun `app prefix is the first two labels by default`() {
        assertEquals("com.example", appPrefix)
        assertEquals("a.b.c", TargetGuard.appPrefixOf("a.b.c.d.e", TargetPolicy(appNamespaceLabels = 3)))
        // Zero / negative labels => empty prefix (only package-local survives).
        assertEquals("", TargetGuard.appPrefixOf("com.example.app", TargetPolicy(appNamespaceLabels = 0)))
        // Fewer labels than requested: take what's there.
        assertEquals("com", TargetGuard.appPrefixOf("com", policy))
    }

    // ---- decision order ------------------------------------------------------

    @Test
    fun `package-local target is allowed`() {
        assertTrue(allowed("aaaa"))
        assertTrue(allowed("a"))
    }

    @Test
    fun `app-prefix target is allowed`() {
        assertTrue(allowed("com.example.app.service.RemoteClient"))
        assertTrue(allowed("com.example.other.Thing")) // still under com.example
    }

    @Test
    fun `reserved targets are denied`() {
        for (
        fqn in listOf(
            "java.lang.Runtime",
            "javax.crypto.Cipher",
            "jdk.internal.misc.Unsafe",
            "sun.misc.Unsafe",
            "com.sun.proxy.Foo",
            "dalvik.system.DexClassLoader",
            "android.app.ActivityManager",
            "androidx.core.app.NotificationCompat",
            "com.android.internal.os.Zygote",
            "kotlin.io.FilesKt",
            "kotlinx.coroutines.BuildersKt",
            "dagger.internal.Provider",
            "com.google.android.gms.Foo",
            "libcore.io.Memory",
            "org.apache.harmony.Foo",
        )
        ) {
            assertFalse(allowed(fqn), "expected DENY for $fqn")
        }
    }

    @Test
    fun `dot-boundary prevents false-positive denylist matches under the app prefix`() {
        // A prefix on the denylist must match on a dot boundary, not a raw
        // substring. `com.example.androidx.Foo` is under the app prefix and the
        // `androidx.` denylist entry must NOT match it (it is not `androidx.`).
        assertTrue(allowed("com.example.androidx.Foo"))
    }

    @Test
    fun `foreign namespace not on denylist is still denied`() {
        // Has a dot, not reserved, not under com.example => fail-closed DENY.
        assertFalse(allowed("javafoo.Bar"))
        assertFalse(allowed("org.thirdparty.Lib"))
    }

    @Test
    fun `reserved beats app-prefix when both could match`() {
        val p = TargetPolicy(denyPrefixes = listOf("com.example.secret."))
        val ap = TargetGuard.appPrefixOf("com.example.app", p)
        // Under app prefix com.example AND under the reserved com.example.secret => DENY.
        assertFalse(TargetGuard.isAllowed("com.example.secret.Vault", ap, p))
        // A sibling under the app prefix but not reserved => ALLOW.
        assertTrue(TargetGuard.isAllowed("com.example.app.Foo", ap, p))
    }

    // ---- normalization -------------------------------------------------------

    @Test
    fun `primitives and void are always allowed`() {
        for (p in listOf("void", "boolean", "byte", "char", "short", "int", "long", "float", "double")) {
            assertTrue(allowed(p), "primitive $p should be allowed")
        }
        // Reflective single-letter array element descriptors.
        assertTrue(allowed("[I"))
        assertTrue(allowed("[[J"))
        // Empty / blank.
        assertTrue(allowed(""))
        assertTrue(allowed("   "))
    }

    @Test
    fun `array markers are stripped to the element class`() {
        // Source-form object arrays.
        assertFalse(allowed("java.lang.String[]"))
        assertFalse(allowed("java.lang.Object[][]"))
        assertTrue(allowed("com.example.app.Foo[]"))
        // Reflective object-array element form.
        assertFalse(allowed("[Ljava.lang.String;"))
        assertFalse(allowed("[[Ljava/lang/Object;"))
        assertTrue(allowed("[Lcom.example.app.Foo;"))
        // Empty inner element => always allow (degenerate).
        assertTrue(allowed("[L;"))
    }

    @Test
    fun `with an empty app prefix only package-local survives the app-prefix rule`() {
        // appNamespaceLabels = 0 => empty app prefix. A dotted, non-reserved,
        // non-allowlisted name falls through to the foreign DENY (the
        // appPrefix.isNotEmpty() guard short-circuits to false).
        val p = TargetPolicy(appNamespaceLabels = 0)
        val ap = TargetGuard.appPrefixOf("com.example.app", p)
        assertEquals("", ap)
        assertFalse(TargetGuard.isAllowed("com.example.app.Foo", ap, p)) // foreign now
        assertTrue(TargetGuard.isAllowed("Local", ap, p)) // package-local still ok
    }

    @Test
    fun `array of object with internal-slash form and a multi-dim primitive`() {
        // `[[D` strips to a single-char primitive body => always allow.
        assertTrue(allowed("[[D"))
        // A reflective object array whose element is foreign => denied.
        assertFalse(allowed("[Lsun/misc/Unsafe;"))
        // A bracketed body that is NOT an object element and NOT single-char:
        // e.g. a stray `[Foo` (no leading L, len>1) is treated as a class body.
        assertFalse(allowed("[Foo.Bar"))
    }

    @Test
    fun `nested classes split namespace on dot only`() {
        assertTrue(allowed("com.example.app.Foo\$Bar"))
        assertFalse(allowed("android.os.Foo\$Bar"))
        // A package-local nested class.
        assertTrue(allowed("aaaa\$bbbb"))
    }

    @Test
    fun `matching is case sensitive`() {
        // `Java.lang...` is not `java.` — but it is foreign (has a dot, not app) => still DENY.
        assertFalse(allowed("Java.lang.Runtime"))
        // An app-prefixed but wrong-case prefix is foreign => DENY.
        assertFalse(allowed("Com.example.app.Foo"))
    }

    // ---- escape hatch --------------------------------------------------------

    @Test
    fun `escape-hatch allowlist permits an otherwise reserved target`() {
        val p = TargetPolicy(allow = listOf("java.lang.Runtime"))
        val ap = TargetGuard.appPrefixOf("com.example.app", p)
        assertTrue(TargetGuard.isAllowed("java.lang.Runtime", ap, p))
        // Array/element-normalized form matches the allowlist entry.
        assertTrue(TargetGuard.isAllowed("[Ljava.lang.Runtime;", ap, p))
        // A different reserved class is still denied.
        assertFalse(TargetGuard.isAllowed("java.lang.System", ap, p))
    }

    // ---- denylist merge/replace ---------------------------------------------

    @Test
    fun `mergeDenylist false replaces the defaults`() {
        val p = TargetPolicy(denyPrefixes = listOf("com.evil."), mergeDenylist = false)
        val ap = TargetGuard.appPrefixOf("com.example.app", p)
        // java.* is no longer reserved (defaults replaced) but is foreign => still DENY.
        assertFalse(TargetGuard.isAllowed("java.lang.Runtime", ap, p))
        // The custom reserved prefix denies even though it is foreign anyway;
        // assert it is on the effective list.
        assertEquals(listOf("com.evil."), p.effectiveDenyPrefixes)
    }

    @Test
    fun `mergeDenylist true augments the defaults`() {
        val p = TargetPolicy(denyPrefixes = listOf("com.example.app.danger."))
        val ap = TargetGuard.appPrefixOf("com.example.app", p)
        assertTrue(p.effectiveDenyPrefixes.containsAll(DEFAULT_DENY_PREFIXES))
        assertTrue(p.effectiveDenyPrefixes.contains("com.example.app.danger."))
        // An app-prefixed class under the custom reserved sub-namespace is denied.
        assertFalse(TargetGuard.isAllowed("com.example.app.danger.Thing", ap, p))
    }

    // ---- assertAllowed throws with structured fields -------------------------

    @Test
    fun `assertAllowed throws TargetPolicyException for a denied target`() {
        val ex =
            assertFailsWith<TargetPolicyException> {
                TargetGuard.assertAllowed("com.example.Real", "java.lang.Runtime", appPrefix, policy)
            }
        assertEquals("com.example.Real", ex.name)
        assertEquals("java.lang.Runtime", ex.target)
        assertTrue(ex.reason.contains("denylist"))
    }

    @Test
    fun `assertAllowed is a no-op for an allowed target`() {
        // Should not throw.
        TargetGuard.assertAllowed("com.example.Real", "com.example.app.Foo", appPrefix, policy)
    }

    @Test
    fun `default deny prefixes match the spec exactly`() {
        assertEquals(
            listOf(
                "java.",
                "javax.",
                "jdk.",
                "sun.",
                "com.sun.",
                "dalvik.",
                "android.",
                "androidx.",
                "com.android.",
                "kotlin.",
                "kotlinx.",
                "dagger.",
                "com.google.android.",
                "libcore.",
                "org.apache.harmony.",
            ),
            DEFAULT_DENY_PREFIXES,
        )
    }

    // ---- defense-in-depth loader check (via the binding chokepoint) ----------

    @Test
    fun `app-prefixed but boot-loaded class is hard-denied by the loader check`() {
        // Isolate the defense-in-depth loader check: make the NAMESPACE gate
        // pass for a boot-loaded class by mapping the app to `java.lang` (so
        // `java.lang.String` is "app-prefixed") and dropping the reserved
        // denylist. The loader check must then hard-deny because String is
        // realised by the bootstrap loader, not the app loader.
        val map =
            MapLoader.fromJson(
                """
                {
                  "schema_version": 2,
                  "app": "java.lang.app",
                  "version": "1.0.0",
                  "version_code": 100,
                  "classes": { "com.example.RealClient": { "obfuscated": "java.lang.String" } }
                }
                """.trimIndent(),
            )
        // app prefix = "java.lang" -> namespace "java.lang.String" passes the
        // app-prefix rule (the reserved `java.` denylist would normally win, so
        // drop it to reach the loader check).
        val p = TargetPolicy(denyPrefixes = emptyList(), mergeDenylist = false)
        val r = RosettaXposed.fromMapUnverified(map, javaClass.classLoader, p)
        val ex = assertFailsWith<TargetPolicyException> { r.useClass("com.example.RealClient").load() }
        assertTrue(ex.reason.contains("boot/system"))
    }

    private val fixtureObf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"

    private fun mapFor(
        app: String,
        obf: String,
    ) = MapLoader.fromJson(
        """
        {
          "schema_version": 2,
          "app": "$app",
          "version": "1.0.0",
          "version_code": 100,
          "classes": { "com.example.RealClient": { "obfuscated": "$obf" } }
        }
        """.trimIndent(),
    )

    @Test
    fun `app-prefixed system-loaded class is hard-denied by the loader check`() {
        // The fixture class is realised by the test JVM's SYSTEM class loader.
        // Map the app so the fixture FQN is app-prefixed (passes the namespace
        // gate) but is NOT allowlisted, so the loader check fires on the
        // system-loader membership branch.
        val r = RosettaXposed.fromMapUnverified(mapFor("io.github.app", fixtureObf), javaClass.classLoader)
        val ex = assertFailsWith<TargetPolicyException> { r.useClass("com.example.RealClient").load() }
        assertTrue(ex.reason.contains("boot/system"))
    }

    @Test
    fun `allowlisted system-loaded class bypasses the loader check`() {
        // Same fixture, but explicitly allowlisted: the loader check is skipped
        // entirely for an escape-hatched FQN, so the load succeeds.
        val r =
            RosettaXposed.fromMapUnverified(
                mapFor("io.github.app", fixtureObf),
                javaClass.classLoader,
                TargetPolicy(allow = listOf(fixtureObf)),
            )
        assertEquals(fixtureObf, r.useClass("com.example.RealClient").load().name)
    }

    @Test
    fun `app-loaded class from a child loader passes the loader check`() {
        // A child loader whose parent is the platform loader will DEFINE the
        // fixture class itself (the platform loader doesn't have it), so the
        // loaded class's loader is neither boot, system, nor platform — the
        // app-loader accept path. This mirrors the real Xposed app class loader.
        val cp =
            System
                .getProperty("java.class.path")
                .split(java.io.File.pathSeparator)
                .map {
                    java.io
                        .File(it)
                        .toURI()
                        .toURL()
                }.toTypedArray()
        val child = java.net.URLClassLoader(cp, ClassLoader.getPlatformClassLoader())
        val r = RosettaXposed.fromMapUnverified(mapFor("io.github.app", fixtureObf), child)
        val loaded = r.useClass("com.example.RealClient").load()
        assertEquals(fixtureObf, loaded.name)
        // It was realised by our child loader, not a platform loader.
        assertTrue(loaded.classLoader === child)
    }
}
