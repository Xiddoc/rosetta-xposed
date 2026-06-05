/*
 * The end-to-end Rosetta walkthrough, on a plain JVM.
 *
 * Every step here has a one-to-one counterpart in the Android LSPosed module
 * under ../android: the same bundled map, the same real names, the same
 * RosettaXposed API. The only thing this harness can't do is apply a real
 * hook (there is no Xposed runtime), so the Hooker simply captures the
 * resolved Member and we invoke it directly to prove the real -> obf
 * translation landed on the right method.
 */
package com.example.victimhook

import com.example.victim.a.b
import io.github.xiddoc.rosetta.core.MapLoader
import io.github.xiddoc.rosetta.core.version.MapRegistry
import io.github.xiddoc.rosetta.core.version.MatchedBy
import io.github.xiddoc.rosetta.core.version.VersionMatch
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.Hooker
import io.github.xiddoc.rosetta.xposed.RosettaXposed
import io.github.xiddoc.rosetta.xposed.TargetPolicy
import io.github.xiddoc.rosetta.xposed.Unhook
import java.lang.reflect.Method

/** What the walkthrough observed, so a test can assert the flow end-to-end. */
public data class WalkthroughResult(
    val matchedBy: MatchedBy,
    val obfClass: String,
    val memberName: String,
    val invoked: String,
)

public object Walkthrough {
    private const val MAP_RESOURCE = "maps/100.json"
    private const val OBF_FQN = "com.example.victim.a.b"
    private const val REAL_CLASS = "com.example.victim.TicketService"
    private const val REAL_METHOD = "formatTicket"

    public fun run(): WalkthroughResult {
        // 1. Load the bundled map — the exact artifact the Android module ships.
        val json =
            Walkthrough::class.java.classLoader
                ?.getResourceAsStream(MAP_RESOURCE)
                ?.use { it.readBytes().decodeToString() }
                ?: error("bundled map '$MAP_RESOURCE' is missing from the classpath")
        val map = MapLoader.fromJson(json)

        // 2. Select the map by the authoritative O(1) version_code key, exactly
        //    as a module does after reading PackageInfo on-device.
        val registry = MapRegistry.of(map)
        val identity =
            AppIdentity(
                packageName = "com.example.victim",
                versionCode = 100,
                versionName = "1.0.0",
                // No signerSha256s: maps/100.json carries no `signer_sha256`, so
                // the SignerGuard is a no-op here. The Android module's
                // AndroidAppIdentity helper shows how to populate this from
                // PackageManager when a map DOES pin a signer.
            )
        val selected =
            VersionMatch.select(registry, identity.versionCode, identity.versionName)
                ?: error("no map for version_code ${identity.versionCode}")

        // 3. Build the binding (this also runs the signer guard fail-closed).
        //    We allowlist the stand-in obf FQN because in THIS plain-JVM harness
        //    the victim class is realised by the SYSTEM class loader, which the
        //    C1 defense-in-depth check treats as a foreign/platform target. On a
        //    real device the app's classes come from the APP class loader, so
        //    this allowlist is NOT needed there (the default policy allows
        //    `com.example.victim.*` because it is under the app's own prefix).
        val policy = TargetPolicy(allow = listOf(OBF_FQN))
        val rosetta =
            RosettaXposed.fromRegistry(registry, identity, javaClass.classLoader, policy)
                ?: error("registry produced no binding for the running identity")

        // 4. Resolve by REAL name; Rosetta hands the obfuscated Member to the
        //    Hooker. A real module applies its framework's hook here; we just
        //    capture the member to prove what was resolved.
        var captured: Method? = null
        rosetta
            .method(REAL_CLASS, REAL_METHOD)
            .hook(
                Hooker { member ->
                    captured = member as Method
                    Unhook { /* a real module returns its framework's unhook */ }
                },
            )
        val member = captured ?: error("the Hooker never received a member")

        // 5. Invoke the resolved obfuscated method to prove the translation
        //    landed on `b#c` and behaves.
        val invoked = member.invoke(b(), "T-123") as String

        return WalkthroughResult(
            matchedBy = selected.matchedBy,
            obfClass = OBF_FQN,
            memberName = member.name,
            invoked = invoked,
        )
    }
}

public fun main() {
    val r = Walkthrough.run()
    println("=== rosetta-xposed walkthrough (plain JVM) ===")
    println("matched by       : ${r.matchedBy}")
    println("real class       : com.example.victim.TicketService")
    println("obfuscated class : ${r.obfClass}")
    println("real method      : formatTicket   ->   obfuscated member: ${r.memberName}")
    println("invocation       : formatTicket(\"T-123\")  ->  ${r.invoked}")
    println("OK — resolved a human name to its obfuscated Member with no hard-coded obf strings.")
}
