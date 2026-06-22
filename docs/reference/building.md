# Building

The project is a four-module Gradle (Kotlin/JVM) build (`:core`, `:xposed`, `:android-runtime`, `:dexkit`). It builds and tests
on a plain JVM — no Android SDK or emulator required.

```sh
./gradlew build          # compile + test both modules (JVM only)
./gradlew :core:test     # run the shared conformance suite
```

The build requests a **JDK 17** toolchain and auto-provisions it (foojay
resolver) if your machine only has a newer JDK. CI runs on JDK 17 and 21.

## Git hooks (opt-in)

A tracked `pre-commit` hook (`gradle/hooks/pre-commit`) runs the formatting
gate before each commit. Installing it is **opt-in** — it is no longer wired
into `build`, because writing into `.git/hooks` as a side effect of an
ordinary build is a filesystem write outside the project tree that the
security audit flagged. Install it once per clone:

```sh
./gradlew installGitHooks
```

The task is worktree-safe: it copies into `.git/hooks` only when `.git` is a
real directory (it skips silently in a `git worktree`, where `.git` is a
pointer file, and in source-tarball / CI checkouts with no `.git`).

## Supply-chain hardening

The build chain is pinned against tampering:

- **Gradle wrapper:** `gradle/wrapper/gradle-wrapper.properties` carries a
  `distributionSha256Sum` so the wrapper refuses to run an unverified
  Gradle distribution.
- **Dependency verification:** `gradle/verification-metadata.xml` pins every
  resolved dependency and plugin by **SHA-256 checksum**
  (`verify-metadata=true`, `verify-signatures=false` — checksum pinning, not
  PGP signatures, which are too brittle across this plugin set). A normal
  `./gradlew build` verifies every artifact against it — adding or bumping a
  dependency will FAIL the build until `gradle/verification-metadata.xml` is
  regenerated with the full-task-set command below. Regenerate after a
  dependency or plugin bump with:

  ```sh
  ./gradlew --write-verification-metadata sha256 \
      spotlessCheck detekt test koverVerify build
  ```

  Run it over the full task set above (not just `help`) so every
  configuration — detekt, Kover, ktlint engine, tests — is resolved and
  pinned.
- **Repositories:** `settings.gradle.kts` keeps
  `RepositoriesMode.FAIL_ON_PROJECT_REPOS`; all plugins are version-pinned.

### CI: Develocity auto-injection is disabled

`gradle/actions/setup-gradle@v4` will, by default, **auto-inject** the
Develocity (build-scan) plugin onto the buildscript `:classpath` via an
init script. That injected plugin drags `org.junit:junit-bom` and
`org.jetbrains.kotlinx:kotlinx-coroutines-bom` onto the classpath from the
Gradle Plugin Portal. Because `gradle/verification-metadata.xml` is
generated locally — where there is no injection — those BOM `.module` /
`.pom` artifacts are **not pinned**, so strict dependency verification
(`verify-metadata=true`) rejects them and CI fails at *configuration* time
with `Dependency verification failed for configuration ':classpath'`. A
local `./gradlew build` passes precisely because the injection only happens
in CI.

We don't publish build scans, so the fix keeps verification **fully
strict** by disabling the injection rather than relaxing the pins. `ci.yml`
sets both:

- the documented action input `develocity-injection-enabled: false` on the
  `setup-gradle` step, and
- the `DEVELOCITY_INJECTION_ENABLED: 'false'` env var at the job level
  (the auto-injection init script reads this env var directly — belt and
  suspenders so injection is off regardless of code path).

With injection off, no unpinned artifacts ever reach the classpath, JAR
checksum verification stays untouched, and `verify-metadata` remains
`true`.

## Publishing

The three pure-JVM modules — `:core`, `:xposed`, `:android-runtime` — publish to
Maven under the group **`io.github.xiddoc.rosetta`**. The optional, native
`:dexkit` adapter is **not** published (it is kept out of the default build —
see [Status](status.md)).

### Coordinates

| Coordinate | Module |
| --- | --- |
| `io.github.xiddoc.rosetta:xposed:0.2.0` | `:xposed` (depends on `:core`) |
| `io.github.xiddoc.rosetta:core:0.2.0` | `:core` |
| `io.github.xiddoc.rosetta:android-runtime:0.2.0` | `:android-runtime` |

Each module publishes a main jar plus `-sources` and `-javadoc` jars and a full
POM (name, description, URL, MIT license, SCM, developer, issue tracker).

### Version scheme

SemVer, with the **MINOR line deliberately tied to the map `schema_version`**
the release consumes:

- `0.4.x` consumes `schema_version: 5`. (`0.3.x` consumed `schema_version: 4`;
  `0.2.x` consumed `schema_version: 3`; `0.1.x` consumed `schema_version: 2`.)
- A breaking schema bump (e.g. to `schema_version: 6`) moves the library to the
  next MINOR (`0.5.x`); a breaking *library API* change before 1.0 also moves the
  MINOR.
- Once the surface is stable the library graduates to `1.0.0` and ordinary
  SemVer applies.

The in-code source of truth is
`io.github.xiddoc.rosetta.core.BuildInfo` (`GROUP` / `VERSION` /
`SCHEMA_VERSION`). `GROUP` and `VERSION` are **generated at build time** from
the Gradle `project.group` / `project.version` (the `generateBuildInfo` task in
`core/build.gradle.kts` writes a generated Kotlin source into the `:core`
compilation — no new plugins), so the constant cannot drift from the published
coordinate: the release tag flows through `-Prosetta.version` into both the
Maven coordinate and `BuildInfo.VERSION`. A unit test (`BuildInfoTest`) asserts
`BuildInfo.VERSION` equals the injected Gradle version and binds
`SCHEMA_VERSION` to the loader's `CURRENT_SCHEMA_VERSION`, so a tag↔constant or
schema↔coordinate mismatch **fails the gate**.

The build's `version` defaults to `0.2.0` but is overridable for a release via
`-Prosetta.version=<x.y.z>`; the [release workflow](#tag-driven-release) feeds it
the git tag (a `v0.2.0` tag publishes `0.2.0`).

### Local install

```sh
./gradlew publishToMavenLocal   # installs all three modules into ~/.m2
```

No signing key is needed locally: signing is **required only when a key is
present** (the publish workflow supplies one). A plain build or
`publishToMavenLocal` skips it.

### Offline-safe by construction

Publishing uses only the **built-in** `maven-publish` and `signing` Gradle
plugins — no new artifacts on the classpath, so nothing new to pin in
`gradle/verification-metadata.xml` and the strict dependency-verification gate
is untouched. The `-javadoc` jar is an **empty placeholder** rather than a Dokka
build: Maven Central only requires a `-javadoc` artifact to exist, and pulling
Dokka would mean network resolution + new verification pins that the offline
default build cannot do. The human-facing API docs live in the MkDocs site
([below](#docs)); if rendered Javadoc becomes a requirement, add Dokka behind a
guard and regenerate the verification metadata, keeping the default `build`
network-free.

### Tag-driven release

`.github/workflows/release.yml` runs on a pushed `v*` tag. It runs the full
quality gate (`spotlessCheck detekt test koverVerify build`), then:

1. **Uploads** the three modules, GPG-signed, to the Sonatype Central Portal via
   the OSSRH Staging API bridge (`ossrh-staging-api.central.sonatype.com`). With
   plain `maven-publish` this only creates an `open` **staging** deployment — it
   does not release anything.
2. **Promotes** that deployment to Maven Central with a `curl` call to the Portal
   promotion endpoint: it `GET`s `/manual/search/repositories?state=open` to find
   the deployment key this run created, then `POST`s
   `/manual/upload/repository/<key>?publishing_type=automatic`, which validates
   and auto-releases if the checks pass. Both calls authenticate with a Bearer
   token = base64 of `CENTRAL_USERNAME:CENTRAL_PASSWORD`. (A nexus-publish Gradle
   plugin would normally do this, but adding any new plugin would break the
   strict dependency-verification invariant, so the promotion is a plain
   `curl`.)

It reads four CI secrets **by name** (never inline): `SIGNING_KEY`
(ASCII-armored GPG private key), `SIGNING_PASSWORD`, `CENTRAL_USERNAME`, and
`CENTRAL_PASSWORD`. Develocity auto-injection is disabled the same way as in
`ci.yml` so strict dependency verification stays green.

**Manual fallback / unverified status.** If the promotion call cannot resolve or
release the deployment (Portal API drift, or Central holding the release for
review), the artifacts remain safely in staging: log in to
<https://central.sonatype.com>, open **Deployments**, and click **Publish** on
the pending deployment to release it by hand. This whole publish-and-promote
path is **wired but not yet verified against a live Central account** — no real
release has been cut, so treat the first `v*` tag as the proving run for the
credentials, signing key, namespace approval, and promotion call. See the
[OSSRH Staging API docs](https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/).

## Docs

The documentation site is a separate, Python-based concern (MkDocs
Material) and is **not** part of the Gradle build. To preview it locally:

```sh
pip install mkdocs-material
mkdocs serve
```
