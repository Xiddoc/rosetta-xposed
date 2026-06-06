/*
 * Compiles a victim source with REAL names, runs standalone R8 to obfuscate it
 * (deterministically, via -applymapping victim/seed.txt) into JVM classfiles,
 * and a test loads that obfuscated jar and proves Rosetta resolves the human
 * names to the obfuscated members R8 actually emitted.
 *
 * No Android SDK: R8 is a plain Maven dependency (see settings.gradle.kts
 * google() repo). `--classfile` makes R8 emit JVM bytecode (not DEX), so the
 * obfuscated victim loads in this very JVM.
 */
plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    google()
    mavenCentral()
}

// R8 as an ordinary resolvable dependency — the "no Android SDK" lever.
val r8 by configurations.creating

dependencies {
    implementation("io.github.xiddoc.rosetta:xposed")
    r8("com.android.tools:r8:8.5.35")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

val victimDir = layout.projectDirectory.dir("victim")
val victimClasses = layout.buildDirectory.dir("victim-classes")
val obfJar = layout.buildDirectory.file("obf/victim-obf.jar")

// 1. javac the victim (real names). Using the JDK's javac directly keeps the
//    task tiny; the victim is one self-contained source file.
val compileVictim by tasks.registering(Exec::class) {
    val src = victimDir.file("src/com/example/victim/TicketService.java").asFile
    val outDir = victimClasses.get().asFile
    inputs.file(src)
    outputs.dir(victimClasses)
    doFirst { outDir.mkdirs() }
    commandLine("javac", "--release", "17", "-d", outDir.path, src.path)
}

// 2. Run R8 over the compiled victim with --classfile output. -applymapping
//    seed.txt pins the obfuscated names so they EXACTLY match the committed map
//    (maps/100.json) — which means this test also guards the map against
//    drifting from what R8 really produces.
val obfuscate by tasks.registering(JavaExec::class) {
    dependsOn(compileVictim)
    classpath = configurations["r8"]
    mainClass.set("com.android.tools.r8.R8")

    val rules = victimDir.file("rules.pro").asFile
    val seed = victimDir.file("seed.txt").asFile
    val inClass = victimClasses.get().file("com/example/victim/TicketService.class").asFile
    val out = obfJar.get().asFile

    inputs.dir(victimClasses)
    inputs.file(rules)
    inputs.file(seed)
    outputs.file(obfJar)

    // R8 resolves `-applymapping seed.txt` relative to its working directory.
    workingDir = victimDir.asFile
    doFirst { out.parentFile.mkdirs() }
    argumentProviders.add {
        listOf(
            "--classfile",
            "--release",
            "--lib", System.getProperty("java.home"),
            "--output", out.path,
            "--pg-conf", rules.path,
            inClass.path,
        )
    }
}

tasks.test {
    dependsOn(obfuscate)
    useJUnitPlatform()
    // Hand the obfuscated jar to the test so it can load it via a child loader.
    systemProperty("rosetta.r8.obfJar", obfJar.get().asFile.path)
    testLogging {
        events("passed", "skipped", "failed")
    }
}
