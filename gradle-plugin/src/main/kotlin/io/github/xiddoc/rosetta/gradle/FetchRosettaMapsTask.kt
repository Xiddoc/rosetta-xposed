/*
 * The `fetchRosettaMaps` task: a thin Gradle wrapper over [RosettaMapsFetcher].
 *
 * Inputs are the app/ref/versions/repo/offline knobs; the output is the maps
 * dir. With a pinned ref and unchanged inputs, Gradle marks the task UP-TO-DATE
 * and the build does NOT hit the network — the warm-cache/offline story. The
 * cache dir is `@Internal` (a location, not an input): it must not invalidate
 * the output.
 */
package io.github.xiddoc.rosetta.gradle

import io.github.xiddoc.rosetta.core.model.CURRENT_SCHEMA_VERSION
import io.github.xiddoc.rosetta.gradle.internal.FetchConfig
import io.github.xiddoc.rosetta.gradle.internal.GitHubTarballSource
import io.github.xiddoc.rosetta.gradle.internal.RosettaMapsFetcher
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

public abstract class FetchRosettaMapsTask : DefaultTask() {
    @get:Input
    public abstract val app: Property<String>

    @get:Input
    public abstract val ref: Property<String>

    @get:Input
    public abstract val versions: ListProperty<Long>

    @get:Input
    public abstract val repo: Property<String>

    @get:Input
    public abstract val offline: Property<Boolean>

    @get:Input
    public abstract val fetchSignatures: Property<Boolean>

    @get:Internal
    public abstract val cacheDirectory: DirectoryProperty

    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    @get:OutputDirectory
    public abstract val signaturesOutputDirectory: DirectoryProperty

    @TaskAction
    public fun fetch() {
        val config =
            FetchConfig(
                repo = repo.get(),
                app = app.get(),
                ref = ref.get(),
                versions = versions.get(),
                offline = offline.get(),
                outputDir = outputDirectory.get().asFile.toPath(),
                cacheDir = cacheDirectory.get().asFile.toPath(),
                currentSchemaVersion = CURRENT_SCHEMA_VERSION,
                fetchSignatures = fetchSignatures.get(),
                signaturesOutputDir = signaturesOutputDirectory.get().asFile.toPath(),
            )
        val fetcher = RosettaMapsFetcher(GitHubTarballSource(), logger::lifecycle)
        val summary = fetcher.fetch(config)
        logger.lifecycle(
            "rosetta-maps: bundled ${summary.written.size} map(s) for ${config.app} " +
                "from ${config.repo}@${config.ref} ${summary.written}" +
                (if (summary.skippedSchema.isEmpty()) "" else " (skipped non-current schema: ${summary.skippedSchema})") +
                (if (summary.signaturesWritten) " + signatures" else ""),
        )
    }
}
