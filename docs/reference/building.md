# Building

The project is a two-module Gradle (Kotlin/JVM) build. It builds and tests
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

## Distribution

This is an Xposed-family module dependency, **not** an npm package.
Distribution is via the usual Xposed/LSPosed/LSPatch mechanisms (build the
module APK and load it through your framework manager) or by depending on
the Kotlin library once it is published to a Maven coordinate. Maven
publishing is **planned** — see [Status](status.md).

## Docs

The documentation site is a separate, Python-based concern (MkDocs
Material) and is **not** part of the Gradle build. To preview it locally:

```sh
pip install mkdocs-material
mkdocs serve
```
