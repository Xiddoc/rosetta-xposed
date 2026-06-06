/*
 * The pure-JVM walkthrough harness.
 *
 * It depends on rosetta-xposed exactly the way an external project will: a
 * single `io.github.xiddoc.rosetta:xposed` coordinate (resolved here from the
 * parent build through the composite `includeBuild` in settings). No Android,
 * no Xposed API, no device — it runs the FULL static resolution path
 * (load map -> select by version_code -> enforce signer guard -> resolve real
 * name -> hand the obfuscated Member to a Hooker) against a plain-JVM stand-in
 * for an obfuscated app class, and is therefore runnable + assertable in CI.
 *
 * The Android example under ../android wires the SAME map + SAME real names
 * into a real LSPosed module; this harness is the part that can be proven
 * without an emulator.
 */
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // The one dependency a consumer needs. `:core` comes transitively (api).
    implementation("io.github.xiddoc.rosetta:xposed")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

application {
    mainClass.set("com.example.victimhook.WalkthroughKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
