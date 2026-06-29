/*
 * `io.github.xiddoc.rosetta.maps` — registers the `rosettaMaps { }` DSL and the
 * `fetchRosettaMaps` task, and (for pure-JVM consumers, the default non-vendor
 * mode) wires the generated maps dir onto the `main` Java-resources class path
 * so `BundledMaps` reads them at runtime exactly as if hand-copied.
 *
 * Android (AGP) consumers add the one srcDir line themselves — the plugin does
 * NOT compile against AGP (that would drag the whole Android toolchain into
 * strict dependency verification), so AGP source-set wiring stays in the
 * consumer's build script, where AGP's types are on the classpath. See
 * docs/getting-started/build-time-maps.md.
 */
package io.github.xiddoc.rosetta.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer

public class RosettaMapsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("rosettaMaps", RosettaMapsExtension::class.java)
        ext.repo.convention(DEFAULT_REPO)
        ext.offline.convention(false)
        ext.vendor.convention(false)
        ext.signatures.convention(true)
        ext.versions.convention(emptyList())
        // outputDirectory / vendorDirectory are RESOURCE ROOTS; the maps land in a
        // `maps/` subdir of the chosen root so `BundledMaps.load("100.json")` reads
        // `maps/100.json` off the class loader, exactly as the hand-copied layout did.
        ext.outputDirectory.convention(project.layout.buildDirectory.dir(GENERATED_ROOT))
        ext.vendorDirectory.convention(project.layout.projectDirectory.dir(VENDOR_ROOT))
        ext.cacheDirectory.convention(
            project.layout.dir(
                project.provider { project.gradle.gradleUserHomeDir.resolve(CACHE_DIR) },
            ),
        )

        val fetch =
            project.tasks.register(TASK_NAME, FetchRosettaMapsTask::class.java) { task ->
                task.group = "rosetta"
                task.description = "Fetches Rosetta maps for the configured app from rosetta-maps at build time."
                task.app.set(ext.app)
                task.ref.set(ext.ref)
                task.versions.set(ext.versions)
                task.repo.set(ext.repo)
                task.offline.set(ext.offline)
                task.fetchSignatures.set(ext.signatures)
                task.cacheDirectory.set(ext.cacheDirectory)
                // Write into `<root>/maps` (+ `<root>/signatures`). Vendor mode
                // targets the source tree; otherwise the generated root.
                val root = ext.vendor.flatMap { if (it) ext.vendorDirectory else ext.outputDirectory }
                task.outputDirectory.set(root.map { it.dir(MAPS_SUBDIR) })
                task.signaturesOutputDirectory.set(root.map { it.dir(SIGNATURES_SUBDIR) })
            }

        // Auto-wire the generated dir onto a pure-JVM module's resources. Gated on
        // (vendor == false): in vendor mode the maps are committed under
        // src/main/resources and picked up normally, so the build must NOT depend
        // on the (network-touching) fetch task. The provider resolves to nothing
        // when vendoring, which drops both the dir AND the task dependency.
        project.pluginManager.withPlugin("org.gradle.java") {
            // Add the generated dir to `main` Java resources. A list-valued provider
            // lets vendor mode contribute NOTHING (empty list) — in vendor mode the
            // maps are committed under src/main/resources and the build must not
            // depend on the network-touching fetch. (Resolving an empty list is safe;
            // a null-valued provider throws.)
            // Register the resource ROOT (which contains the `maps/` subdir), so the
            // packaged resource path is `maps/<version_code>.json`.
            val generated: Provider<List<Directory>> =
                ext.vendor.flatMap { vendoring ->
                    if (vendoring) project.provider { emptyList() } else ext.outputDirectory.map { listOf(it) }
                }
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            sourceSets.named("main").configure { it.resources.srcDir(generated) }

            // Make resource processing run the fetch first — but not in vendor mode.
            // The Callable is evaluated lazily when the task graph is built, so the
            // `vendor` flag is read after the consumer's build script has set it.
            project.tasks.named("processResources").configure { res ->
                res.dependsOn(project.provider { if (ext.vendor.get()) emptyList<Any>() else listOf(fetch) })
            }
        }
    }

    private companion object {
        const val TASK_NAME = "fetchRosettaMaps"
        const val DEFAULT_REPO = "Xiddoc/rosetta-maps"
        const val GENERATED_ROOT = "generated/rosetta-maps"
        const val VENDOR_ROOT = "src/main/resources"
        const val MAPS_SUBDIR = "maps"
        const val SIGNATURES_SUBDIR = "signatures"
        const val CACHE_DIR = "rosetta-maps-cache"
    }
}
