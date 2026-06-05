/*
 * DexKitBackedIndexIntegrationTest — a hermetic, end-to-end test that runs the
 * REAL DexKit native against a committed, R8-obfuscated `classes.dex` fixture
 * and proves the whole dynamic-discovery path through [DexKitBackedIndex]:
 *
 *   1. anchor / superclass / AIDL-descriptor class discovery,
 *   2. method-by-signature discovery,
 *   3. the composite self-healing write-back (index consulted at most once),
 *   4. the C1 target guard wrapping a DISCOVERED name,
 *   5. the signer guard running BEFORE discovery (fail-closed).
 *
 * It is hermetic: the dex fixture + its real→obf mapping + the host-built
 * `libdexkit.so` all live under `src/test/resources/` and are committed. The
 * test JVM is given `-Djava.library.path=.../native/linux-x86_64` by the build
 * script so DexKit's `System.loadLibrary("dexkit")` resolves the native.
 *
 * GRACEFUL SKIP. On a runner WITHOUT the native (or the fixture), the setup
 * skips cleanly via JUnit `Assumptions` so the suite stays green everywhere —
 * which is also why this module's coverage is NOT wired into the root 100%
 * Kover gate. On THIS runner the native IS present, so the assertions actually
 * run against real DexKit.
 */
package io.github.xiddoc.rosetta.dexkit

import io.github.xiddoc.rosetta.core.SignerMismatchException
import io.github.xiddoc.rosetta.core.TargetPolicyException
import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.CompositeResolutionBackend
import io.github.xiddoc.rosetta.xposed.DexKitIndex
import io.github.xiddoc.rosetta.xposed.DiscoveryConfig
import io.github.xiddoc.rosetta.xposed.DiscoveryHints
import io.github.xiddoc.rosetta.xposed.DynamicResolutionBackend
import io.github.xiddoc.rosetta.xposed.MethodDiscoveryHint
import io.github.xiddoc.rosetta.xposed.MethodMatch
import io.github.xiddoc.rosetta.xposed.MethodQuery
import io.github.xiddoc.rosetta.xposed.RosettaXposed
import io.github.xiddoc.rosetta.xposed.StaticResolutionBackend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ONE shared [DexKitBridge] for the whole class (`PER_CLASS` lifecycle +
 * `@BeforeAll`/`@AfterAll`). DexKit's native side is happiest with a single
 * bridge per JVM: creating and closing a fresh bridge per test method churns
 * native allocations and can destabilise the runtime (observed SIGABRT). The
 * bridge is read-only across the discovery tests, so sharing it is safe and
 * faster.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DexKitBackedIndexIntegrationTest {
    private lateinit var mapping: FixtureMapping

    // The single live bridge + adapter under test, created once and closed once.
    private var bridge: DexKitBridge? = null
    private lateinit var index: DexKitBackedIndex

    // Whether the real-DexKit path is available on this host. When false, every
    // test skips cleanly; when true (this runner), they actually execute.
    private var ready = false

    @BeforeAll
    fun setUpClass() {
        val dexFile = resource("dex/fixture.dex") ?: return
        val mapFile = resource("dex/fixture-mapping.json") ?: return
        mapping = FixtureMapping.parse(mapFile.readText())

        // The native is mandatory for a real run. Load it explicitly (DexKit's
        // own tests do the same); on a host without it / wrong arch we leave
        // `ready` false so every test skips cleanly — but DO run where it loads.
        bridge =
            try {
                System.loadLibrary("dexkit")
                DexKitBridge.create(arrayOf(dexFile.readBytes()))
            } catch (t: Throwable) {
                println("rosetta-xposed:dexkit: native unavailable (${t.javaClass.simpleName}) — tests will skip.")
                return
            }
        index = DexKitBackedIndex(bridge!!)
        ready = true
    }

    @AfterAll
    fun tearDownClass() {
        bridge?.close()
    }

    @BeforeEach
    fun requireRealDexKit() {
        assumeTrue(ready, "DexKit fixture or native not available on this host — skipping.")
    }

    // --- 1. Anchor discovery -------------------------------------------------

    @Test
    fun `anchor discovery resolves AnchoredWidget directly and end-to-end`() {
        val widget = mapping.cls("com.rosetta.dexfixture.AnchoredWidget")
        val anchor = widget.anchors.single()

        // Direct seam call.
        assertEquals(
            widget.obfuscated,
            index.findClassByAnchors(listOf(anchor)),
            "anchor discovery must resolve to the obfuscated AnchoredWidget",
        )

        // End-to-end through the dynamic backend (the real discovery flow).
        val backend =
            DynamicResolutionBackend(
                index = index,
                hints = mapOf("AnchoredWidget" to DiscoveryHints(anchors = listOf(anchor))),
            )
        assertEquals(
            widget.obfuscated,
            backend.resolveClass("AnchoredWidget").obfName,
            "dynamic backend must surface the discovered obfuscated AnchoredWidget",
        )
    }

    // --- 2. Superclass discovery --------------------------------------------

    @Test
    fun `superclass discovery resolves NetworkHandler by its obfuscated parent`() {
        val network = mapping.cls("com.rosetta.dexfixture.NetworkHandler")
        val parent = network.extends!!

        assertEquals(
            network.obfuscated,
            index.findClassBySuperclass(parent),
            "superclass discovery must resolve NetworkHandler from its obfuscated parent",
        )
    }

    // --- 3. AIDL-descriptor discovery ---------------------------------------

    @Test
    fun `aidl-descriptor discovery resolves RemoteStub by its descriptor literal`() {
        val stub = mapping.cls("com.rosetta.dexfixture.RemoteStub")
        val descriptor = stub.aidlDescriptor!!

        assertEquals(
            stub.obfuscated,
            index.findClassByAidlDescriptor(descriptor),
            "AIDL-descriptor discovery must resolve RemoteStub by its descriptor string literal",
        )
    }

    // --- 4. Method-by-signature discovery -----------------------------------

    @Test
    fun `method-by-signature discovery resolves NetworkHandler process`() {
        val network = mapping.cls("com.rosetta.dexfixture.NetworkHandler")
        val process = network.methods.getValue("process")

        // Direct seam call by signature.
        val direct =
            index.findMethod(
                MethodQuery(
                    declaringClass = network.obfuscated,
                    paramTypes = listOf("java.lang.String"),
                    returnType = "java.lang.String",
                ),
            )
        assertEquals(
            MethodMatch(network.obfuscated, process.obfuscated, process.signature),
            direct,
            "method-by-signature must resolve process to its obfuscated name + descriptor",
        )

        // End-to-end through the dynamic backend, locating the class by
        // superclass then the method by signature.
        val backend =
            DynamicResolutionBackend(
                index = index,
                hints =
                    mapOf(
                        "NetworkHandler" to
                            DiscoveryHints(
                                superclass = network.extends!!,
                                methods =
                                    listOf(
                                        MethodDiscoveryHint(
                                            realName = "process",
                                            paramTypes = listOf("java.lang.String"),
                                            returnType = "java.lang.String",
                                        ),
                                    ),
                            ),
                    ),
            )
        val resolved = backend.resolveMethod("NetworkHandler", "process", argTypes = null)
        assertEquals(process.obfuscated, resolved.obfName, "discovered method obf name")
        assertEquals(network.obfuscated, resolved.className, "discovered method declaring class")
        assertEquals(process.signature, resolved.signature, "discovered method descriptor")
    }

    // --- 5. Composite write-back (index consulted at most once) --------------

    @Test
    fun `composite write-back consults the index once then serves a static hit`() {
        val widget = mapping.cls("com.rosetta.dexfixture.AnchoredWidget")
        val counting = CountingDexKitIndex(index)
        val composite =
            CompositeResolutionBackend(
                static = StaticResolutionBackend(emptyFixtureMap()),
                dynamic =
                    DynamicResolutionBackend(
                        index = counting,
                        hints = mapOf("AnchoredWidget" to DiscoveryHints(anchors = widget.anchors)),
                    ),
            )

        // First lookup: a static miss falls through to discovery.
        assertEquals(widget.obfuscated, composite.resolveClass("AnchoredWidget").obfName)
        val afterFirst = counting.calls
        assertTrue(afterFirst > 0, "first lookup must consult the DexKit index")

        // Second lookup: a written-back static hit; the index is NOT touched.
        assertEquals(widget.obfuscated, composite.resolveClass("AnchoredWidget").obfName)
        assertEquals(
            afterFirst,
            counting.calls,
            "second lookup must be an O(1) static hit and not re-query the index",
        )
    }

    // --- 6. C1 target guard wraps a DISCOVERED name --------------------------

    @Test
    fun `discovery flow routes a discovered reserved name through the C1 guard`() {
        // Real DexKit cannot emit a `java.*` name from our app dex, so a tiny
        // local stub injects one through the SAME discovery flow to prove the
        // guard wraps DISCOVERED names (not just static ones).
        val poisonIndex =
            object : DexKitIndex {
                override fun findClassByAnchors(anchors: List<String>): String = "java.lang.Runtime"

                override fun findClassByAidlDescriptor(descriptor: String): String? = null

                override fun findClassBySuperclass(superName: String): String? = null

                override fun findMethod(query: MethodQuery): MethodMatch? = null

                override fun membersOf(obfClass: String): List<MethodMatch> = emptyList()
            }

        val rosetta =
            RosettaXposed.fromMapWithDiscovery(
                map = emptyFixtureMap(),
                index = poisonIndex,
                classLoader = javaClass.classLoader,
                discovery =
                    DiscoveryConfig(
                        hints = mapOf("Evil" to DiscoveryHints(anchors = listOf("anything"))),
                    ),
            )

        assertFailsWith<TargetPolicyException>(
            "a discovered name on a reserved namespace must be rejected before any class load",
        ) {
            rosetta.useClass("Evil").load()
        }
    }

    // --- 7. Signer guard runs BEFORE discovery -------------------------------

    @Test
    fun `signer guard fails closed before discovery ever runs`() {
        val counting = CountingDexKitIndex(index)
        val signedMap =
            RosettaMap(
                schemaVersion = 2,
                app = "com.rosetta.dexfixture",
                version = "1.0.0",
                versionCode = 1L,
                signerSha256 = "a".repeat(64),
                classes = emptyMap(),
            )
        val mismatched =
            AppIdentity(
                packageName = "com.rosetta.dexfixture",
                versionCode = 1L,
                signerSha256s = setOf("b".repeat(64)),
            )

        assertFailsWith<SignerMismatchException>("a signer mismatch must fail closed") {
            RosettaXposed.fromMapWithDiscovery(
                map = signedMap,
                index = counting,
                classLoader = javaClass.classLoader,
                identity = mismatched,
                discovery =
                    DiscoveryConfig(
                        hints =
                            mapOf(
                                "AnchoredWidget" to DiscoveryHints(anchors = mapping.cls("com.rosetta.dexfixture.AnchoredWidget").anchors),
                            ),
                    ),
            )
        }

        assertEquals(0, counting.calls, "discovery must NOT run when the signer guard rejects the map")
    }

    // --- helpers -------------------------------------------------------------

    /** An empty, well-formed schema-2 map for the running fixture app. */
    private fun emptyFixtureMap(): RosettaMap =
        RosettaMap(
            schemaVersion = 2,
            app = "com.rosetta.dexfixture",
            version = "1.0.0",
            versionCode = 1L,
            classes = emptyMap(),
        )

    private fun resource(path: String): File? {
        val url = javaClass.classLoader.getResource(path) ?: return null
        return File(url.toURI()).takeIf { it.isFile }
    }
}

/**
 * A counting [DexKitIndex] decorator. The real [DexKitBridge] is not a counter,
 * so this wraps the real adapter to let the write-back test observe how many
 * times the index was queried.
 */
private class CountingDexKitIndex(
    private val delegate: DexKitIndex,
) : DexKitIndex {
    var calls: Int = 0
        private set

    override fun findClassByAidlDescriptor(descriptor: String): String? {
        calls++
        return delegate.findClassByAidlDescriptor(descriptor)
    }

    override fun findClassByAnchors(anchors: List<String>): String? {
        calls++
        return delegate.findClassByAnchors(anchors)
    }

    override fun findClassBySuperclass(superName: String): String? {
        calls++
        return delegate.findClassBySuperclass(superName)
    }

    override fun findMethod(query: MethodQuery): MethodMatch? {
        calls++
        return delegate.findMethod(query)
    }

    override fun membersOf(obfClass: String): List<MethodMatch> {
        calls++
        return delegate.membersOf(obfClass)
    }
}

// --- fixture-mapping.json model (the test's source of truth) -----------------

/** One method's expected real→obf mapping from `fixture-mapping.json`. */
private data class FixtureMethod(
    val obfuscated: String,
    val signature: String,
)

/** One class's expected real→obf mapping from `fixture-mapping.json`. */
private data class FixtureClass(
    val obfuscated: String,
    val anchors: List<String>,
    val extends: String?,
    val aidlDescriptor: String?,
    val methods: Map<String, FixtureMethod>,
)

/** The parsed `fixture-mapping.json` — the expected values the test asserts. */
private class FixtureMapping(
    private val classes: Map<String, FixtureClass>,
) {
    fun cls(real: String): FixtureClass = classes[real] ?: error("fixture-mapping.json has no class '$real'")

    companion object {
        fun parse(text: String): FixtureMapping {
            val root = Json.parseToJsonElement(text).jsonObject
            val classesObj = root.getValue("classes").jsonObject
            val classes =
                classesObj.entries.associate { (real, raw) ->
                    val obj = raw.jsonObject
                    real to
                        FixtureClass(
                            obfuscated = obj.getValue("obfuscated").jsonPrimitive.content,
                            anchors = obj.stringList("anchors"),
                            extends = obj.stringOrNull("extends"),
                            aidlDescriptor = obj.stringOrNull("aidlDescriptor"),
                            methods = obj.methods(),
                        )
                }
            return FixtureMapping(classes)
        }

        private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.content

        private fun JsonObject.stringList(key: String): List<String> =
            (this[key] as? kotlinx.serialization.json.JsonArray)
                ?.map { it.jsonPrimitive.content }
                .orEmpty()

        private fun JsonObject.methods(): Map<String, FixtureMethod> =
            (this["methods"] as? JsonObject)
                ?.entries
                ?.associate { (name, raw) ->
                    val m = raw.jsonObject
                    name to
                        FixtureMethod(
                            obfuscated = m.getValue("obfuscated").jsonPrimitive.content,
                            signature = m.getValue("signature").jsonPrimitive.content,
                        )
                }.orEmpty()
    }
}
