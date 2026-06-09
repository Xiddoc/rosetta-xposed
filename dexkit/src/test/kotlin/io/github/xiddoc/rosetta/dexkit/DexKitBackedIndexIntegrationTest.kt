/*
 * DexKitBackedIndexIntegrationTest — a hermetic, end-to-end test that runs the
 * REAL DexKit native against a committed, R8-obfuscated `classes.dex` fixture
 * and proves the whole dynamic-discovery path through [DexKitBackedIndex]:
 *
 *   1. anchor / superclass / AIDL-descriptor class discovery,
 *   2. method-by-signature discovery (return+param, returnType-only,
 *      paramTypes-only, and the usingStrings facet),
 *   3. member enumeration ([DexKitBackedIndex.membersOf]) + its miss branch,
 *   4. the composite self-healing write-back (index consulted at most once),
 *   5. the C1 target guard wrapping a DISCOVERED name,
 *   6. the signer guard running BEFORE discovery (fail-closed).
 *
 * The dex fixture + its real→obf mapping live under `src/test/resources/` and
 * are committed. The host-built `libdexkit.so`, by contrast, is NOT committed:
 * it is BUILT IN CI (cached) from pinned DexKit 2.2.0 source via
 * `tools/dexkit-native/build-libdexkit.sh`, and locally you build it on demand.
 *
 * PRESENT ⇒ RUN, ABSENT ⇒ SKIP. The build (`dexkit/build.gradle.kts`) supplies
 * the test JVM with both `-Djava.library.path=.../native/linux-x86_64` AND the
 * directory itself as the `rosetta.dexkit.nativeDir` system property. `@BeforeAll`
 * first probes for `File(nativeDir, System.mapLibraryName("dexkit"))` and then
 * loads the native + creates the bridge as SEPARATE steps. The cases:
 *
 *   - The native file is ABSENT (a fresh checkout that has not run the build
 *     script, or an arch CI never builds for): there is simply nothing to load,
 *     so the suite SKIPS cleanly via `assumeTrue(ready)` on ALL platforms,
 *     including linux-x86_64. This is the normal local-dev path.
 *   - The native file is PRESENT but `System.loadLibrary("dexkit")` /
 *     `DexKitBridge.create` fails with `UnsatisfiedLinkError`: a real binary is
 *     here but broken (e.g. an older glibc than the GLIBC_2.38 floor, or a
 *     drifted build). On a SUPPORTED platform (linux + amd64/x86_64 — i.e. CI
 *     on ubuntu-latest, which builds the native) this is FATAL: setup FAILS
 *     with a pointer at the GLIBC_2.38 floor and `build-libdexkit.sh`. On an
 *     UNSUPPORTED arch a present-but-unloadable lib is benign and skips.
 *   - Any OTHER throwable (e.g. `DexKitBridge.create` choking on a broken dex
 *     once the lib loaded) is a real regression and is left to PROPAGATE.
 *
 * Net effect: CI builds the native, finds it present, and runs every test for
 * real — going red on any genuine failure — while a local/dev checkout without
 * the built native skips cleanly. Because the suite legitimately skips on those
 * machines, this module's coverage is NOT wired into the root 100% Kover gate.
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

        // STEP 0 — probe for the native file itself. It is NOT committed (CI
        // builds it via build-libdexkit.sh); on a fresh checkout that has not
        // run that script, the file is simply ABSENT. That is the normal
        // local-dev / unsupported-arch path: nothing to load, so leave `ready`
        // false and every test skips cleanly via `assumeTrue` — on ALL
        // platforms, linux-x86_64 included.
        val libFile = nativeLibFile()
        if (libFile == null || !libFile.isFile) {
            println(
                "rosetta-xposed:dexkit: native libdexkit.so not built here " +
                    "(${libFile?.path ?: "rosetta.dexkit.nativeDir unset"}) — tests will skip. " +
                    "Build it with tools/dexkit-native/build-libdexkit.sh to run them.",
            )
            return
        }

        // STEP 1 — the native file IS present, so load it (separately from
        // bridge creation). A load failure now means a real binary is here but
        // broken (e.g. an older glibc than the GLIBC_2.38 floor, or a drifted
        // build). On a SUPPORTED platform (linux + amd64/x86_64, i.e. CI) that
        // is FATAL: a present-but-unloadable native must go red, not skip. On
        // an UNSUPPORTED arch it is benign — leave `ready` false to skip.
        try {
            System.loadLibrary("dexkit")
        } catch (e: UnsatisfiedLinkError) {
            if (isSupportedPlatform()) throw nativeLoadFailure(e)
            println("rosetta-xposed:dexkit: present native unloadable on this arch (${e.javaClass.simpleName}) — tests will skip.")
            return
        } catch (e: NoClassDefFoundError) {
            // DexKit class itself missing from the classpath — unavailable.
            if (isSupportedPlatform()) throw nativeLoadFailure(e)
            println("rosetta-xposed:dexkit: DexKit classes unavailable (${e.javaClass.simpleName}) — tests will skip.")
            return
        }

        // STEP 2 — create the bridge. This is NOT in the skip-gate: any failure
        // here (e.g. DexKitBridge.create choking on a broken dex) is a real
        // regression and is allowed to PROPAGATE and fail the run. An
        // UnsatisfiedLinkError out of create() on a supported platform is still
        // a present-but-broken native, so it is also routed to the fatal path.
        try {
            bridge = DexKitBridge.create(arrayOf(dexFile.readBytes()))
        } catch (e: UnsatisfiedLinkError) {
            if (isSupportedPlatform()) throw nativeLoadFailure(e)
            println("rosetta-xposed:dexkit: present native unloadable on this arch (${e.javaClass.simpleName}) — tests will skip.")
            return
        }
        index = DexKitBackedIndex(bridge!!)
        ready = true
    }

    /**
     * The expected native file location, derived from the `rosetta.dexkit.nativeDir`
     * system property the build supplies (the directory also on `java.library.path`).
     * Returns `null` if the property is unset; otherwise the platform-mapped
     * `libdexkit.so` under it (which may or may not exist — the caller probes).
     */
    private fun nativeLibFile(): File? {
        val dir = System.getProperty("rosetta.dexkit.nativeDir").orEmpty()
        if (dir.isEmpty()) return null
        return File(dir, System.mapLibraryName("dexkit"))
    }

    /**
     * The platform where a PRESENT native is expected to load: Linux on an
     * x86_64 / amd64 JVM (the CI runner that builds it). On any other platform a
     * present-but-unloadable native is benign and the suite skips.
     */
    private fun isSupportedPlatform(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return os.contains("linux") && (arch == "amd64" || arch == "x86_64")
    }

    private fun nativeLoadFailure(cause: Throwable): AssertionError =
        AssertionError(
            "A PRESENT DexKit native failed to load on a SUPPORTED platform " +
                "(${System.getProperty("os.name")}/${System.getProperty("os.arch")}). " +
                "The native at ${nativeLibFile()?.path} exists but is not loadable here. " +
                "It requires GLIBC_2.38 or newer and is BUILT IN CI (not committed) via " +
                "tools/dexkit-native/build-libdexkit.sh — rebuild it on a host meeting the GLIBC_2.38 floor. " +
                "Cause: ${cause.javaClass.name}: ${cause.message}",
            cause,
        )

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

    // --- 4b. findMethod optional-facet branches (returnType / paramTypes /
    //         usingStrings), each exercised in isolation -----------------------

    @Test
    fun `findMethod resolves process by returnType only`() {
        val network = mapping.cls("com.rosetta.dexfixture.NetworkHandler")
        val process = network.methods.getValue("process")

        // Drives the `query.returnType?.let { ... }` branch alone: no param
        // facet. process is the only String-returning method on NetworkHandler.
        val match =
            index.findMethod(
                MethodQuery(
                    declaringClass = network.obfuscated,
                    returnType = "java.lang.String",
                ),
            )
        assertEquals(
            MethodMatch(network.obfuscated, process.obfuscated, process.signature),
            match,
            "returnType-only findMethod must resolve process",
        )
    }

    @Test
    fun `findMethod resolves process by paramTypes only`() {
        val network = mapping.cls("com.rosetta.dexfixture.NetworkHandler")
        val process = network.methods.getValue("process")

        // Drives the `query.paramTypes?.let { ... }` branch alone: no return
        // facet. process is the only (String)-taking method on NetworkHandler.
        val match =
            index.findMethod(
                MethodQuery(
                    declaringClass = network.obfuscated,
                    paramTypes = listOf("java.lang.String"),
                ),
            )
        assertEquals(
            MethodMatch(network.obfuscated, process.obfuscated, process.signature),
            match,
            "paramTypes-only findMethod must resolve process",
        )
    }

    @Test
    fun `findMethod resolves getAnchor by its unique usingStrings literal`() {
        val widget = mapping.cls("com.rosetta.dexfixture.AnchoredWidget")
        val anchor = widget.anchors.single()

        // Drives the `usingStrings` facet: AnchoredWidget.getAnchor() is the
        // sole method that references the (globally unique) anchor literal, so
        // it resolves even without return/param facets. This proves the
        // usingStrings arm of findMethod against the real bridge.
        val match =
            index.findMethod(
                MethodQuery(
                    declaringClass = widget.obfuscated,
                    usingStrings = listOf(anchor),
                ),
            )
        assertTrue(
            match != null && match.declaringClass == widget.obfuscated,
            "usingStrings findMethod must resolve a method on the obfuscated AnchoredWidget",
        )
        // getAnchor returns String and takes no args; cross-check the descriptor
        // shape rather than the obfuscated name (which the mapping does not pin).
        assertEquals(
            "()Ljava/lang/String;",
            match!!.descriptor,
            "the usingStrings-matched method must be getAnchor()Ljava/lang/String;",
        )
    }

    // --- 4c. membersOf: hit enumerates the class, miss is empty --------------

    @Test
    fun `membersOf enumerates the obfuscated NetworkHandler and includes process`() {
        val network = mapping.cls("com.rosetta.dexfixture.NetworkHandler")
        val process = network.methods.getValue("process")

        val members = index.membersOf(network.obfuscated)
        assertTrue(members.isNotEmpty(), "membersOf must enumerate the class's methods")
        assertTrue(
            members.contains(MethodMatch(network.obfuscated, process.obfuscated, process.signature)),
            "membersOf must include process with its obfuscated name + descriptor",
        )
    }

    @Test
    fun `membersOf returns empty for a class not in the dex`() {
        // Drives the `getClassData(...)?...orEmpty()` null/miss branch.
        assertEquals(
            emptyList(),
            index.membersOf("com.rosetta.dexfixture.does.not.Exist"),
            "membersOf must return empty for a class absent from the dex",
        )
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
                // `String?` mirrors the seam + the real adapter (a non-null
                // return would narrow the contract). Still resolves a reserved
                // name to prove the guard wraps DISCOVERED names.
                override fun findClassByAnchors(anchors: List<String>): String? = "java.lang.Runtime"

                override fun findClassByAidlDescriptor(descriptor: String): String? = null

                override fun findClassBySuperclass(superName: String): String? = null

                override fun findMethod(query: MethodQuery): MethodMatch? = null

                override fun membersOf(obfClass: String): List<MethodMatch> = emptyList()
            }

        // Wrap the poison index so we can prove the discovered name actually
        // reached the guard (non-vacuous), mirroring the signer-guard test.
        val counting = CountingDexKitIndex(poisonIndex)
        val rosetta =
            RosettaXposed.fromMapWithDiscovery(
                map = emptyFixtureMap(),
                index = counting,
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
        assertTrue(
            counting.calls > 0,
            "discovery must have run — the reserved name must reach the C1 guard via the index",
        )
    }

    // --- 7. Signer guard runs BEFORE discovery -------------------------------

    @Test
    fun `signer guard fails closed before discovery ever runs`() {
        val counting = CountingDexKitIndex(index)
        val signedMap =
            RosettaMap(
                schemaVersion = 3,
                app = "com.rosetta.dexfixture",
                version = "1.0.0",
                versionCode = 1L,
                signerSha256s = listOf("a".repeat(64)),
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
            schemaVersion = 3,
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
