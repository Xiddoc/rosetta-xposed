/*
 * Library build coordinates — the single source of truth, in code, for the
 * published Maven group / version and the schema version they track.
 *
 * The Gradle publishing wiring (root build.gradle.kts `version`/`group`) and
 * this object are coordinated by an asserted invariant: a test pins
 * [BuildInfo.version] to the schema-coordinated `0.1.0` line and pins
 * [BuildInfo.schemaVersion] to the loader's [model.CURRENT_SCHEMA_VERSION], so
 * the published artifact can never silently drift from the schema it speaks.
 *
 * Version scheme (documented in docs/reference/building.md): the library uses
 * SemVer, with the MINOR line deliberately tied to the map `schema_version`
 * it consumes — `0.1.x` speaks `schema_version: 2`. A breaking schema bump
 * (to `schema_version: 3`) moves the library to the next MINOR (`0.2.x`); a
 * breaking *library API* change before 1.0 also moves the MINOR. Once the
 * surface is stable this graduates to `1.0.0` and ordinary SemVer applies.
 */
package io.github.xiddoc.rosetta.core

import io.github.xiddoc.rosetta.core.model.CURRENT_SCHEMA_VERSION

/**
 * Compile-time constants describing the published library artifact: its Maven
 * [group], its released [version], and the map [schemaVersion] that version
 * tracks. These exist so consumers (and tooling) can read the coordinates the
 * same artifact was published under without parsing build metadata, and so a
 * unit test can hold the Gradle version and the schema version in lockstep.
 */
public object BuildInfo {
    /** The Maven group all three published modules share. */
    public const val GROUP: String = "io.github.xiddoc.rosetta"

    /**
     * The released library version (SemVer). The MINOR line is coordinated
     * with [schemaVersion] — see the file header for the full scheme. Kept in
     * sync with the Gradle `version` in the root build script by a unit test.
     */
    public const val VERSION: String = "0.1.0"

    /**
     * The map `schema_version` this library release consumes, mirrored from
     * [CURRENT_SCHEMA_VERSION] so the published coordinate and the loader's
     * hard gate cannot drift apart.
     */
    public const val SCHEMA_VERSION: Int = CURRENT_SCHEMA_VERSION
}
