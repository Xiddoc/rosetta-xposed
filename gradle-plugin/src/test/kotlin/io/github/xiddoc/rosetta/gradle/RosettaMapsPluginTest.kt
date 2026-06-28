package io.github.xiddoc.rosetta.gradle

import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RosettaMapsPluginTest {
    private fun project() = ProjectBuilder.builder().build()

    @Test
    fun `registers the extension with sensible defaults and the fetch task`() {
        val project = project()
        project.pluginManager.apply(RosettaMapsPlugin::class.java)

        val ext = project.extensions.getByType(RosettaMapsExtension::class.java)
        assertEquals("Xiddoc/rosetta-maps", ext.repo.get())
        assertFalse(ext.offline.get())
        assertFalse(ext.vendor.get())
        assertTrue(ext.signatures.get()) // signatures baked by default
        assertTrue(ext.versions.get().isEmpty())

        val task = project.tasks.findByName("fetchRosettaMaps")
        assertNotNull(task)
        assertTrue(task is FetchRosettaMapsTask)
    }

    @Test
    fun `wires the signatures output dir under the generated root`() {
        val project = project()
        project.pluginManager.apply(RosettaMapsPlugin::class.java)
        val ext = project.extensions.getByType(RosettaMapsExtension::class.java)
        ext.app.set("com.example.victim")
        ext.ref.set("abc123")

        val task = project.tasks.findByName("fetchRosettaMaps") as FetchRosettaMapsTask
        assertTrue(task.fetchSignatures.get())
        val sigDir = task.signaturesOutputDirectory.get().asFile
        assertTrue(sigDir.path.endsWith("generated/rosetta-maps/signatures"), "got ${sigDir.path}")
    }

    @Test
    fun `applies cleanly to a project with no java or android plugin`() {
        // Bare project: the task still registers; nothing is wired onto a source set.
        val project = project()
        project.pluginManager.apply(RosettaMapsPlugin::class.java)
        assertNotNull(project.tasks.findByName("fetchRosettaMaps"))
    }

    @Test
    fun `wires the generated maps dir onto main java resources by default`() {
        val project = project()
        project.pluginManager.apply(JavaPlugin::class.java)
        project.pluginManager.apply(RosettaMapsPlugin::class.java)

        val ext = project.extensions.getByType(RosettaMapsExtension::class.java)
        ext.app.set("com.example.victim")
        ext.ref.set("abc123")

        val generated =
            project.layout.buildDirectory
                .dir("generated/rosetta-maps")
                .get()
                .asFile
        val resourceDirs = mainResourceDirs(project)
        assertTrue(generated in resourceDirs, "expected generated dir among $resourceDirs")
    }

    @Test
    fun `does not wire the generated dir in vendor mode`() {
        val project = project()
        project.pluginManager.apply(JavaPlugin::class.java)
        project.pluginManager.apply(RosettaMapsPlugin::class.java)

        val ext = project.extensions.getByType(RosettaMapsExtension::class.java)
        ext.app.set("com.example.victim")
        ext.ref.set("abc123")
        ext.vendor.set(true)

        val generated =
            project.layout.buildDirectory
                .dir("generated/rosetta-maps")
                .get()
                .asFile
        assertFalse(generated in mainResourceDirs(project), "vendor mode must not auto-wire the generated dir")
    }

    private fun mainResourceDirs(project: org.gradle.api.Project): Set<java.io.File> {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        return sourceSets.getByName("main").resources.srcDirs
    }
}
