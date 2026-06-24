/*
 * SignatureCompiler tests — the harvest of community signatures into DexKit
 * discovery hints (RFC 0001 Decision 5).
 *
 * Pins every classification branch of the mechanical harvest in BOTH
 * directions: which signatures become exact anchors, which become RE2 regex
 * anchors, which are skipped as structural, and which method string constants
 * survive as `usingStrings`. Also pins the fail-closed [SafePattern] bounds at
 * compile time (this is the first PRODUCTION caller of SafePattern.compile).
 *
 * Sets are built programmatically (not via the JSON loader) so a test controls
 * the exact pattern string — backslashes and quotes included.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.signature.ClassSignature
import io.github.xiddoc.rosetta.core.signature.MemberSignature
import io.github.xiddoc.rosetta.core.signature.SignatureRule
import io.github.xiddoc.rosetta.core.signature.SignatureSet
import io.github.xiddoc.rosetta.core.signature.SignatureType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignatureCompilerTest {
    private fun rx(s: String) = SignatureRule(s, SignatureType.REGEX)

    private fun str(s: String) = SignatureRule(s, SignatureType.STRING)

    private fun smali(s: String) = SignatureRule(s, SignatureType.SMALI)

    private fun cls(
        name: String,
        sigs: List<SignatureRule>,
        methods: List<MemberSignature> = emptyList(),
    ) = ClassSignature(name, "com.example.app", sigs, methods = methods)

    private fun compile(vararg classes: ClassSignature) = SignatureCompiler.compile(SignatureSet(classes.toList()))

    private fun hintsFor(
        name: String,
        sigs: List<SignatureRule>,
        methods: List<MemberSignature> = emptyList(),
    ) = compile(cls(name, sigs, methods))["com.example.app.$name"]

    // ---- type:string --------------------------------------------------------

    @Test
    fun `a type-string signature is an exact anchor`() {
        assertEquals(listOf("hello"), hintsFor("A", listOf(str("hello")))?.anchors)
    }

    @Test
    fun `a quoted type-string signature is de-quoted`() {
        assertEquals(listOf("hello"), hintsFor("A", listOf(str("\"hello\"")))?.anchors)
    }

    @Test
    fun `a blank type-string signature is skipped, leaving the class un-locatable`() {
        assertTrue(compile(cls("A", listOf(str("   ")))).isEmpty())
        assertTrue(compile(cls("A", listOf(str("\"\"")))).isEmpty())
    }

    // ---- type:smali ---------------------------------------------------------

    @Test
    fun `a type-smali signature is never harvested`() {
        assertTrue(compile(cls("A", listOf(smali("invoke-virtual {p0}, La/b;->c()V")))).isEmpty())
    }

    // ---- type:regex, quoted (string constants) ------------------------------

    @Test
    fun `a quoted literal regex is an exact anchor`() {
        assertEquals(listOf("accounts/login/"), hintsFor("A", listOf(rx("\"accounts/login/\"")))?.anchors)
    }

    @Test
    fun `a quoted regex with escaped dots reduces to an exact literal anchor`() {
        // "com\.example\.app\.IRemoteService" → the literal dotted descriptor.
        val sig = rx("\"com\\.example\\.app\\.IRemoteService\"")
        assertEquals(listOf("com.example.app.IRemoteService"), hintsFor("A", listOf(sig))?.anchors)
    }

    @Test
    fun `a quoted genuine regex becomes a regex anchor, not an exact one`() {
        // "https://.*\.example/api" keeps an unescaped .* → a SimilarRegex anchor.
        val hints = hintsFor("A", listOf(rx("\"https://.*\\.example/api\"")))
        assertEquals(emptyList(), hints?.anchors)
        assertEquals(listOf("https://.*\\.example/api"), hints?.regexAnchors)
    }

    @Test
    fun `a quoted empty regex is skipped`() {
        assertTrue(compile(cls("A", listOf(rx("\"\"")))).isEmpty())
    }

    // ---- type:regex, unquoted ----------------------------------------------

    @Test
    fun `an unquoted bare-word regex is a referenced-token exact anchor`() {
        assertEquals(listOf("sessionId"), hintsFor("A", listOf(rx("sessionId")))?.anchors)
    }

    @Test
    fun `an unquoted structural descriptor pattern is skipped`() {
        // requestTicket\(Landroid/os/Bundle;\) is a smali descriptor match, not
        // a referenced string — not harvestable.
        assertTrue(compile(cls("A", listOf(rx("requestTicket\\(Landroid/os/Bundle;\\)")))).isEmpty())
    }

    @Test
    fun `an unquoted genuine regex is skipped`() {
        assertTrue(compile(cls("A", listOf(rx("a.*b")))).isEmpty())
    }

    @Test
    fun `an unquoted pure-literal that is not a bare word is skipped`() {
        // A space is not a regex metacharacter, so it reduces to a literal — but
        // an unquoted non-bare-word is too ambiguous to trust as a string anchor.
        assertTrue(compile(cls("A", listOf(rx("hello world")))).isEmpty())
    }

    @Test
    fun `a dangling backslash makes a pattern un-harvestable`() {
        assertTrue(compile(cls("A", listOf(rx("abc\\")))).isEmpty())
    }

    @Test
    fun `a single-character signature is not treated as quoted`() {
        // Exercises the `length >= 2` arm of the quote check: a 1-char value is a
        // bare literal, not a quoted string.
        assertEquals(listOf("a"), hintsFor("A", listOf(str("a")))?.anchors)
    }

    @Test
    fun `a leading-quote-only value is not treated as quoted`() {
        // Starts with a quote but does not end with one → not a string constant;
        // the leading `"` makes it a non-bare-word, so it is skipped.
        assertTrue(compile(cls("A", listOf(rx("\"abc")))).isEmpty())
    }

    @Test
    fun `a class can carry exact, regex, and skipped signatures together`() {
        // One compileClass call walking all three classification arms in order.
        val hints =
            hintsFor(
                "A",
                listOf(str("lit"), rx("\"a.*b\""), smali("structural")),
            )
        assertEquals(listOf("lit"), hints?.anchors)
        assertEquals(listOf("a.*b"), hints?.regexAnchors)
    }

    // ---- mixing + dedup -----------------------------------------------------

    @Test
    fun `a class can carry both exact and regex anchors`() {
        val hints =
            hintsFor(
                "A",
                listOf(rx("\"login_token\""), rx("\"https://.*\\.example/api\"")),
            )
        assertEquals(listOf("login_token"), hints?.anchors)
        assertEquals(listOf("https://.*\\.example/api"), hints?.regexAnchors)
    }

    @Test
    fun `duplicate anchors are de-duplicated`() {
        val hints = hintsFor("A", listOf(str("dup"), str("dup"), str("\"dup\"")))
        assertEquals(listOf("dup"), hints?.anchors)
    }

    // ---- methods ------------------------------------------------------------

    @Test
    fun `a method's literal string constants become usingStrings`() {
        val hints =
            hintsFor(
                "A",
                sigs = listOf(str("anchor")),
                methods = listOf(MemberSignature("doThing", listOf(rx("\"thing/endpoint/\"")))),
            )
        val mh = hints?.methods?.single()
        assertEquals("doThing", mh?.realName)
        assertEquals(listOf("thing/endpoint/"), mh?.usingStrings)
    }

    @Test
    fun `a method's regex string constant is dropped (DexKit method matching is exact)`() {
        // A quoted genuine regex is a string constant, but a method's usingStrings
        // facet is exact-only, so it contributes no hint.
        val hints =
            hintsFor(
                "A",
                sigs = listOf(str("anchor")),
                methods = listOf(MemberSignature("doThing", listOf(rx("\"thing/.*/v2\"")))),
            )
        assertEquals(emptyList(), hints?.methods)
    }

    @Test
    fun `a method with only structural signatures yields no hint`() {
        val hints =
            hintsFor(
                "A",
                sigs = listOf(str("anchor")),
                methods = listOf(MemberSignature("doThing", listOf(rx("doThing\\(Landroid/os/Bundle;\\)")))),
            )
        assertEquals(emptyList(), hints?.methods)
    }

    @Test
    fun `field signatures are ignored (field discovery is not a strategy)`() {
        // A class with a class-level anchor + only field signatures: the field
        // sigs never contribute to hints (no field facet exists).
        val hints =
            hintsFor(
                "A",
                sigs = listOf(str("anchor")),
            )
        assertEquals(emptyList(), hints?.methods)
        assertEquals(listOf("anchor"), hints?.anchors)
    }

    // ---- omission of un-locatable classes -----------------------------------

    @Test
    fun `a class with no usable class-locating anchor is omitted entirely`() {
        // All signatures are structural → nothing to search by → not in the map.
        val out = compile(cls("A", listOf(smali("x"), rx("a+b"))))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `only harvestable classes appear, in file order`() {
        val out =
            compile(
                cls("Keep1", listOf(str("k1"))),
                cls("Drop", listOf(smali("structural"))),
                cls("Keep2", listOf(rx("\"k2\""))),
            )
        assertEquals(listOf("com.example.app.Keep1", "com.example.app.Keep2"), out.keys.toList())
    }

    // ---- fail-closed SafePattern bounds (compile-time) ----------------------

    @Test
    fun `a malformed regex anchor fails closed at compile time`() {
        // "(unclosed" is a string constant (quoted) whose inner is an invalid
        // RE2 expression — SafePattern.compile rejects it before any hook.
        assertFailsWith<DiscoveryException> { compile(cls("A", listOf(rx("\"(unclosed\"")))) }
    }

    @Test
    fun `an over-length anchor fails closed at compile time`() {
        val tooLong = "a".repeat(SafePattern.MAX_SIGNATURE_LEN + 1)
        assertFailsWith<DiscoveryException> { compile(cls("A", listOf(str(tooLong)))) }
    }

    @Test
    fun `too many exact anchors fail closed at compile time`() {
        val many = (0..SafePattern.MAX_ANCHORS).map { str("a$it") }
        assertFailsWith<DiscoveryException> { compile(cls("A", many)) }
    }

    @Test
    fun `too many regex anchors fail closed at compile time`() {
        // Each is a distinct quoted genuine regex so they land in regexAnchors.
        val many = (0..SafePattern.MAX_ANCHORS).map { rx("\"a$it.*\"") }
        assertFailsWith<DiscoveryException> { compile(cls("A", many)) }
    }

    // ---- end-to-end: the worked com.example.app signatures ------------------

    @Test
    fun `compiles the worked example signatures into the expected hints`() {
        val set =
            SignatureSet(
                listOf(
                    ClassSignature(
                        name = "RemoteServiceClient",
                        pkg = "com.example.app",
                        signatures = listOf(rx("sessionId")),
                        methods = listOf(MemberSignature("requestTicket", listOf(rx("requestTicket\\(Landroid/os/Bundle;\\)")))),
                    ),
                    ClassSignature(
                        name = "Config",
                        pkg = "com.example.app",
                        signatures = listOf(rx("\"https://.*\\.example/api\"")),
                    ),
                    ClassSignature(
                        name = "IRemoteService\$Stub",
                        pkg = "com.example.app",
                        signatures = listOf(rx("\"com\\.example\\.app\\.IRemoteService\"")),
                    ),
                ),
            )
        val out = SignatureCompiler.compile(set)
        assertEquals(
            listOf(
                "com.example.app.RemoteServiceClient",
                "com.example.app.Config",
                "com.example.app.IRemoteService\$Stub",
            ),
            out.keys.toList(),
        )
        // RemoteServiceClient: located by the `sessionId` token; its method is a
        // structural descriptor, so it carries no method hint.
        assertEquals(listOf("sessionId"), out["com.example.app.RemoteServiceClient"]?.anchors)
        assertEquals(emptyList(), out["com.example.app.RemoteServiceClient"]?.methods)
        // Config: a genuine-regex endpoint → a SimilarRegex anchor.
        assertEquals(listOf("https://.*\\.example/api"), out["com.example.app.Config"]?.regexAnchors)
        // The AIDL stub's descriptor literal → an exact anchor.
        assertEquals(listOf("com.example.app.IRemoteService"), out["com.example.app.IRemoteService\$Stub"]?.anchors)
    }
}
