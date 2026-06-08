/*
 * Shared Maven-publishing convention, applied via `apply(from = ...)` by each
 * PUBLISHED module (:core, :xposed, :xposed-android). :dexkit is deliberately
 * excluded — it is the optional, native-dependent adapter kept out of the
 * default build (CLAUDE.md Decision 5).
 *
 * Design choices that keep `./gradlew build` GREEN and OFFLINE-SAFE:
 *
 *  - Only the BUILT-IN `maven-publish` and `signing` Gradle plugins are used.
 *    They ship with the Gradle distribution, so they pull NO new artifacts
 *    onto the classpath and need no entry in gradle/verification-metadata.xml.
 *    The strict dependency-verification gate is untouched.
 *
 *  - The `-javadoc` jar is an EMPTY jar produced by a plain `Jar` task, NOT a
 *    Dokka build. Dokka would have to be resolved from the network and pinned
 *    in verification-metadata.xml, which the offline default build cannot do;
 *    Maven Central only requires that a `-javadoc` artifact EXISTS, not that it
 *    has rendered API docs. The human-facing docs live in the MkDocs site. See
 *    docs/reference/building.md ("Publishing").
 *
 *  - The `-sources` jar comes from Kotlin's `withSourcesJar()`, which is also
 *    built in.
 *
 *  - Signing is REQUIRED only when actually publishing (a real signing key is
 *    present via the `signing.*` properties or the in-memory env-fed key the
 *    release workflow sets). Without a key the signing tasks are skipped, so a
 *    plain `./gradlew build` / `publishToMavenLocal` never needs GPG.
 */

plugins.apply("maven-publish")
plugins.apply("signing")

// A jar named `<artifact>-<version>-javadoc.jar`. Empty on purpose (see header).
val javadocJar =
    tasks.register<Jar>("javadocJar") {
        archiveClassifier.set("javadoc")
        // Marker so the jar isn't silently empty-and-mysterious to anyone who
        // unpacks it; points at where the real docs live.
        from(rootProject.layout.projectDirectory.file("gradle/javadoc-placeholder.txt"))
    }

// Emit a `-sources` jar from the Kotlin/JVM source sets (built-in).
extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
    withSourcesJar()
}

extensions.configure<PublishingExtension> {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
            artifact(javadocJar)

            // Coordinates: groupId/version come from the project (root sets
            // group = io.github.xiddoc.rosetta, version = rosetta.version).
            // artifactId defaults to the module name (core / xposed /
            // xposed-android).
            pom {
                name.set("rosetta-${project.name}")
                description.set(
                    "Hook obfuscated Android apps from Xposed / LSPosed / LSPatch modules using real " +
                        "Java names — the ${project.name} module of the Rosetta cross-framework binding.",
                )
                url.set("https://github.com/Xiddoc/rosetta-xposed")
                inceptionYear.set("2026")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/Xiddoc/rosetta-xposed/blob/master/LICENSE")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("Xiddoc")
                        name.set("Xiddoc")
                        url.set("https://github.com/Xiddoc")
                    }
                }
                scm {
                    url.set("https://github.com/Xiddoc/rosetta-xposed")
                    connection.set("scm:git:https://github.com/Xiddoc/rosetta-xposed.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Xiddoc/rosetta-xposed.git")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/Xiddoc/rosetta-xposed/issues")
                }
            }
        }
    }

    // The remote repository the release workflow publishes to. Credentials and
    // URL come from properties/env the CI secrets provide; absent locally, the
    // repo is simply unusable for publish (publishToMavenLocal still works).
    repositories {
        maven {
            name = "centralPortal"
            // Sonatype Central Portal OSSRH-compatible endpoint. Snapshot vs
            // release is selected by the version suffix.
            val releasesUrl = "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
            val snapshotsUrl = "https://central.sonatype.com/repository/maven-snapshots/"
            url =
                uri(
                    if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl,
                )
            credentials {
                username = (findProperty("centralUsername") as String?) ?: System.getenv("CENTRAL_USERNAME")
                password = (findProperty("centralPassword") as String?) ?: System.getenv("CENTRAL_PASSWORD")
            }
        }
    }
}

extensions.configure<SigningExtension> {
    // In-memory ASCII-armored key + passphrase fed by the release workflow from
    // GPG secrets. Both env vars are read; if unset, no key is configured.
    val signingKey: String? = System.getenv("SIGNING_KEY") ?: (findProperty("signingKey") as String?)
    val signingPassword: String? =
        System.getenv("SIGNING_PASSWORD") ?: (findProperty("signingPassword") as String?)
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(extensions.getByType<PublishingExtension>().publications["maven"])
    }
    // Only enforce a signature when a key is actually present, so the default
    // offline build (which never publishes) does not require GPG.
    isRequired = !signingKey.isNullOrBlank()
}
