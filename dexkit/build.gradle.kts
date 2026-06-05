/*
 * :dexkit — the REAL on-device DexKit adapter (RFC 0001 Decision 5).
 *
 * This module implements the pure-JVM `DexKitIndex` seam declared in :xposed
 * with `DexKitBackedIndex`, the SOLE file in the codebase that imports
 * `org.luckypray.dexkit`. DexKit stays an OPTIONAL later-phase dependency: it
 * is NOT a compile-time dependency of the production :xposed binding (which
 * keeps building + unit-testing on a plain JVM with a fake index), and its
 * coverage is deliberately NOT wired into the root 100% Kover gate (the
 * integration test legitimately skips on machines without the native lib).
 *
 * Consuming the DexKit API in a pure-JVM module
 * ---------------------------------------------
 * DexKit ships as an Android AAR. We don't want the Android plugin here, so an
 * artifact transform extracts `classes.jar` out of the AAR and we depend on
 * that jar. The AAR coordinate is declared NON-TRANSITIVE so Gradle never tries
 * to resolve DexKit's Android-only transitive `dev.rikka.ndk.thirdparty:cxx`
 * (which is not on Maven Central). DexKit is `compileOnly` for the adapter (not
 * a leaked runtime dep) and `testImplementation` for the integration test (it
 * needs the bridge at runtime), alongside DexKit's own flatbuffers runtime dep.
 */
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
}

// ---------------------------------------------------------------------------
// AAR → classes.jar artifact transform.
//
// Registers a transform from the `aar` artifact type to a synthetic
// `dexkit-classes-jar` type that extracts the `classes.jar` entry from inside
// the AAR zip. We then resolve the DexKit dependency asking for that type, so
// the pure-JVM compile/test classpaths see the DexKit `.class` files without
// the Android Gradle plugin.
// ---------------------------------------------------------------------------
abstract class ExtractClassesJar : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val aar = inputArtifact.get().asFile
        val out = outputs.file("${aar.nameWithoutExtension}-classes.jar")
        ZipFile(aar).use { zip ->
            val entry =
                zip.getEntry("classes.jar")
                    ?: error("rosetta-xposed:dexkit: no classes.jar inside AAR ${aar.name}")
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
    }
}

val dexkitClassesJar = "dexkit-classes-jar"

dependencies {
    artifactTypes.maybeCreate("aar")
    registerTransform(ExtractClassesJar::class) {
        from.attribute(
            org.gradle.api.attributes.Attribute
                .of("artifactType", String::class.java),
            "aar",
        )
        to.attribute(
            org.gradle.api.attributes.Attribute
                .of("artifactType", String::class.java),
            dexkitClassesJar,
        )
    }
}

// A request for the extracted classes.jar of the DexKit AAR. `isTransitive =
// false` stops Gradle resolving DexKit's Android-only transitive deps; we add
// flatbuffers (its real runtime dep) explicitly for the test runtime below.
val dexkitAar by configurations.creating {
    isTransitive = false
    attributes {
        attribute(
            org.gradle.api.attributes.Attribute
                .of("artifactType", String::class.java),
            dexkitClassesJar,
        )
    }
}

dependencies {
    // The seam + backends + guards (DexKitIndex, DynamicResolutionBackend,
    // CompositeResolutionBackend, RosettaXposed, the C1/signer guards).
    api(project(":xposed"))

    dexkitAar("org.luckypray:dexkit:2.2.0")

    // DexKit on the adapter's COMPILE classpath only — it is not a runtime dep
    // of the production binding (compileOnly), so :xposed consumers never drag
    // DexKit in transitively. The extracted classes.jar arrives via dexkitAar.
    compileOnly(files(dexkitAar))

    // The integration test runs REAL DexKit, so it needs the bridge classes +
    // DexKit's flatbuffers runtime dep at test runtime.
    testImplementation(files(dexkitAar))
    // flatbuffers-java MUST track the flatbuffers runtime that DexKit 2.2.0
    // bundles/generates against (DexKit's generated FlatBuffers readers are
    // ABI-coupled to this runtime) — bump the two together when bumping DexKit.
    // It is a LEAF dependency (verified: no transitives on testRuntimeClasspath),
    // so pinning the one coordinate is sufficient.
    testImplementation("com.google.flatbuffers:flatbuffers-java:23.5.26")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    // The integration test parses `fixture-mapping.json` (its source of truth)
    // with kotlinx-serialization's JSON DOM. :core uses this internally but as
    // an `implementation` dep, so it does not leak onto our classpath — declare
    // it explicitly for the test.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }

    // Let DexKit's `System.loadLibrary("dexkit")` find the committed host-built
    // native (`libdexkit.so`). Resolve the resources dir to an absolute path and
    // PREPEND it to any existing `java.library.path` so the test JVM can load it.
    val nativeDir =
        layout.projectDirectory
            .dir("src/test/resources/native/linux-x86_64")
            .asFile.absolutePath
    val existingLibPath = System.getProperty("java.library.path").orEmpty()
    val mergedLibPath =
        if (existingLibPath.isEmpty()) nativeDir else "$nativeDir${File.pathSeparator}$existingLibPath"
    jvmArgs("-Djava.library.path=$mergedLibPath")
}
