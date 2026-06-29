/*
 * fromMapWithSignatures wiring tests — the community-signature-driven
 * self-healing construction path (RFC 0001 Decision 5).
 *
 * Proves the factory harvests signatures into discovery hints and inherits the
 * fromMapWithDiscovery posture (signer guard, merge-over of explicit hints, the
 * empty-static-map fall-through to discovery). Runs on a plain JVM with a
 * [FakeDexKitIndex]; the C1 / signer-guard internals are covered by the sibling
 * CompositeDiscoveryWiringTest, so these focus on the signature bridge.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.UnverifiedDiscoveryException
import io.github.xiddoc.rosetta.core.signature.ClassSignature
import io.github.xiddoc.rosetta.core.signature.MemberSignature
import io.github.xiddoc.rosetta.core.signature.SignatureRule
import io.github.xiddoc.rosetta.core.signature.SignatureSet
import io.github.xiddoc.rosetta.core.signature.SignatureType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignatureDiscoveryWiringTest {
    private val obf = "io.github.xiddoc.rosetta.xposed.fixtures.ObfClient"
    private val real = "com.example.RealClient"
    private val policy = TargetPolicy(allow = listOf(obf))

    private fun emptyStaticMap() =
        MapLoader.fromJson(
            """
            { "schema_version": 5, "app": "com.example.app", "version": "1.0.0", "version_code": 100, "classes": {} }
            """.trimIndent(),
        )

    private fun signedMap() =
        MapLoader.fromJson(
            """
            { "schema_version": 5, "app": "com.example.app", "version": "1.0.0", "version_code": 100,
              "signer_sha256": "${"a".repeat(64)}", "classes": {} }
            """.trimIndent(),
        )

    /** A one-class signature set locating [real] by an exact string anchor. */
    private fun anchorSignatures(anchor: String) =
        SignatureSet(
            listOf(ClassSignature("RealClient", "com.example", listOf(SignatureRule("\"$anchor\"", SignatureType.REGEX)))),
        )

    @Test
    fun `discovers an unmapped class via its community signature anchor`() {
        val index = FakeDexKitIndex(byAnchors = mapOf(listOf("tok1") to obf))
        val rosetta =
            RosettaXposed.fromMapWithSignatures(
                map = emptyStaticMap(),
                index = index,
                signatures = anchorSignatures("tok1"),
                classLoader = javaClass.classLoader,
                policy = policy,
            )
        assertTrue(rosetta.knows(real))
        assertEquals(obf, rosetta.useClass(real).load().name)
    }

    @Test
    fun `discovers via a genuine-regex community signature anchor`() {
        // A quoted regex endpoint compiles to a regexAnchors facet → SimilarRegex.
        val patterns = listOf("https://.*\\.example/api")
        val index = FakeDexKitIndex(byPatterns = mapOf(patterns to obf))
        val signatures =
            SignatureSet(
                listOf(
                    ClassSignature(
                        "RealClient",
                        "com.example",
                        listOf(SignatureRule("\"https://.*\\.example/api\"", SignatureType.REGEX)),
                    ),
                ),
            )
        val rosetta =
            RosettaXposed.fromMapWithSignatures(
                map = emptyStaticMap(),
                index = index,
                signatures = signatures,
                classLoader = javaClass.classLoader,
                policy = policy,
            )
        assertEquals(obf, rosetta.useClass(real).load().name)
    }

    @Test
    fun `an explicit hint overrides the compiled signature hint for the same name`() {
        // Compiled anchor maps to a WRONG obf; the explicit AIDL hint (which the
        // merge layers on top) maps to the right one and wins.
        val index =
            FakeDexKitIndex(
                byAnchors = mapOf(listOf("tok1") to "io.github.xiddoc.rosetta.xposed.fixtures.Wrong"),
                byAidl = mapOf("Lcom/example/IFoo;" to obf),
            )
        val rosetta =
            RosettaXposed.fromMapWithSignatures(
                map = emptyStaticMap(),
                index = index,
                signatures = anchorSignatures("tok1"),
                classLoader = javaClass.classLoader,
                discovery = DiscoveryConfig(hints = mapOf(real to DiscoveryHints(aidlDescriptor = "Lcom/example/IFoo;"))),
                policy = policy,
            )
        assertEquals(obf, rosetta.useClass(real).load().name)
    }

    @Test
    fun `self-heals a kept-name stringless method from a community signature - the TickTick Pro-gate shape`() {
        // The mechanical harvest can pin the CLASS (a string anchor) but NOT the
        // method: `isPro` is anchored only by a STRUCTURAL `.method` line, which
        // is dropped at harvest — exactly TickTick's `User#isPro()Z`. DexKit then
        // enumerates the kept member, so the kept-name harvest (#47) lets
        // fromMapWithSignatures resolve it end-to-end with NO hand-authored hint.
        val index =
            FakeDexKitIndex(
                byAnchors = mapOf(listOf("user isPro= ") to obf),
                methods = mapOf(obf to listOf(MethodMatch(obf, "isPro", "()Z"))),
            )
        val signatures =
            SignatureSet(
                listOf(
                    ClassSignature(
                        name = "RealClient",
                        pkg = "com.example",
                        signatures = listOf(SignatureRule("\"user isPro= \"", SignatureType.REGEX)),
                        methods =
                            listOf(
                                MemberSignature(
                                    name = "isPro",
                                    signatures = listOf(SignatureRule("\\.method public isPro\\(\\)Z", SignatureType.REGEX)),
                                ),
                            ),
                    ),
                ),
            )
        val rosetta =
            RosettaXposed.fromMapWithSignatures(
                map = emptyStaticMap(),
                index = index,
                signatures = signatures,
                classLoader = javaClass.classLoader,
                policy = policy,
            )
        assertEquals("isPro", rosetta.method(real, "isPro").resolved.obfName)
    }

    @Test
    fun `a class with no harvestable signature is simply unknown`() {
        // The only signature is a structural smali fragment → not harvested → the
        // class is omitted from the hints, so the binding does not know it.
        val signatures =
            SignatureSet(
                listOf(ClassSignature("RealClient", "com.example", listOf(SignatureRule("invoke x", SignatureType.SMALI)))),
            )
        val index = FakeDexKitIndex()
        val rosetta =
            RosettaXposed.fromMapWithSignatures(
                map = emptyStaticMap(),
                index = index,
                signatures = signatures,
                classLoader = javaClass.classLoader,
                policy = policy,
            )
        assertTrue(!rosetta.knows(real))
        assertEquals(0, index.calls)
    }

    @Test
    fun `inherits the signer guard - a signed map with no identity fails closed`() {
        val index = FakeDexKitIndex(byAnchors = mapOf(listOf("tok1") to obf))
        assertFailsWith<UnverifiedDiscoveryException> {
            RosettaXposed.fromMapWithSignatures(
                map = signedMap(),
                index = index,
                signatures = anchorSignatures("tok1"),
                classLoader = javaClass.classLoader,
            )
        }
        assertEquals(0, index.calls)
    }

    @Test
    fun `a signed map with a matching identity proceeds to signature discovery`() {
        val index = FakeDexKitIndex(byAnchors = mapOf(listOf("tok1") to obf))
        val identity =
            AppIdentity(packageName = "com.example.app", versionCode = 100, signerSha256s = setOf("a".repeat(64)))
        val rosetta =
            RosettaXposed.fromMapWithSignatures(
                map = signedMap(),
                index = index,
                signatures = anchorSignatures("tok1"),
                classLoader = javaClass.classLoader,
                identity = identity,
                policy = policy,
            )
        assertEquals(obf, rosetta.useClass(real).load().name)
    }

    @Test
    fun `with all defaults omitted it constructs and harvests the signatures`() {
        // Omits identity / discovery / policy / allowUnverified so their default
        // expressions execute; an unsigned map needs no opt-in.
        val index = FakeDexKitIndex(byAnchors = mapOf(listOf("tok1") to obf))
        val rosetta =
            RosettaXposed.fromMapWithSignatures(
                emptyStaticMap(),
                index,
                anchorSignatures("tok1"),
                javaClass.classLoader,
            )
        // The default policy denies the fixture obf (outside the app namespace),
        // but the binding KNOWS the name (the signature harvested + wired).
        assertTrue(rosetta.knows(real))
    }
}
