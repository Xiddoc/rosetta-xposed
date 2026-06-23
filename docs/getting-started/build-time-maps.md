# Fetching maps at build time

A Rosetta module ships its maps **baked into the APK** — they are read off the
module class loader at runtime by
[`BundledMaps`](https://github.com/Xiddoc/rosetta-xposed/blob/master/android-runtime/src/main/kotlin/io/github/xiddoc/rosetta/android/BundledMaps.kt),
never fetched on-device (RFC 0001: the on-device process has no network-fetch
path by design). The question is only **how the JSON gets into the artifact at
build time**.

Historically a module *hand-copied* the published JSON from
[`rosetta-maps`](https://github.com/Xiddoc/rosetta-maps)
(`maps/<app>/<version_code>.json`) into its own
`src/main/resources/maps/<version_code>.json`. That copy is a maintenance
footgun: it drifts from the upstream source of truth, carries no provenance, and
grows by hand as you support more app versions.

The **`io.github.xiddoc.rosetta.maps` Gradle plugin** replaces that copy with a
pinned, reproducible **build-time fetch**. You declare *what* you want; the build
pulls it into a generated resources dir and bundles it exactly as before. The
on-device runtime path is byte-identical — this adds **no** runtime download.

## The bundling contract

A bundled map is a Java resource at **`maps/<version_code>.json`** on the
module's class path. `BundledMaps.load("<version_code>.json")` reads it back.
The plugin writes the fetched maps into `<root>/maps/` and registers `<root>` as
a resource directory, so the packaged path is exactly `maps/<version_code>.json`
— whether the maps were hand-copied or fetched, the runtime sees the same thing.

Alongside the maps the plugin emits **`maps/index.json`**, a manifest of what was
bundled and where it came from:

```json
{
  "generated_by": "rosetta-xposed:fetchRosettaMaps",
  "app": "com.example.victim",
  "schema_version": 5,
  "source": { "repo": "Xiddoc/rosetta-maps", "ref": "f16288d…" },
  "maps": [
    { "version_code": 100, "file": "100.json", "blob_sha": "1acbdb6c…" },
    { "version_code": 101, "file": "101.json", "blob_sha": "2e6e004b…" }
  ]
}
```

The `blob_sha` is each file's **git blob object id** (`git hash-object`), so a
build is traceable to exact bytes; `source.ref` records the rosetta-maps revision
the build pinned.

## Pure-JVM modules

For a `java`/`java-library`/`kotlin("jvm")` module the plugin auto-wires the
generated dir onto `main` Java resources:

```kotlin
plugins {
    kotlin("jvm")
    id("io.github.xiddoc.rosetta.maps")
}

rosettaMaps {
    app.set("com.ticktick.task")
    // Pin a commit SHA or tag for reproducibility/provenance:
    ref.set("<rosetta-maps commit SHA or tag>")
    // versions left unset = every version published under maps/<app>/.
    // versions.set(listOf(8080L, 8081L)) // …or a subset
}
```

That's it — `./gradlew build` fetches the maps and bundles them.

## Android (AGP) modules

The plugin never compiles against the Android Gradle Plugin (that would drag the
whole Android toolchain into the library build), so an Android module adds **one
line** to wire the generated dir into its resources, where AGP's types are on the
classpath:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.github.xiddoc.rosetta.maps")
}

rosettaMaps {
    app.set("com.ticktick.task")
    ref.set("<rosetta-maps commit SHA or tag>")
}

android {
    // Bundle the fetched maps as Java resources (read by BundledMaps at runtime).
    sourceSets["main"].resources.srcDirs(layout.buildDirectory.dir("generated/rosetta-maps"))
}

// Run the fetch before anything consumes resources/dex.
tasks.named("preBuild") { dependsOn("fetchRosettaMaps") }
```

The worked example is
[`examples/android/module`](https://github.com/Xiddoc/rosetta-xposed/blob/master/examples/android/module/build.gradle.kts),
which commits **zero** map JSON and fetches `com.example.victim` at a pinned ref.

## Reproducibility, caching, and offline builds

- **Pin the `ref`.** A commit SHA or tag makes the build reproducible and the
  source revision auditable. A branch name works but is mutable, so a warm cache
  may serve a stale snapshot. Transport integrity already comes from
  git-over-HTTPS (rosetta-maps removed its `.sha256` sidecar as redundant) — the
  plugin records each file's git blob SHA for provenance, not a new digest.
- **Caching.** Fetched maps are cached under
  `<gradleUserHome>/rosetta-maps-cache`, keyed by `(repo, ref, app)`. A pinned ref
  is immutable content, so a warm cache is reused **without re-fetching** — even
  online. With unchanged inputs the `fetchRosettaMaps` task is `UP-TO-DATE` and
  the build never touches the network.
- **Offline.** `offline.set(true)` reuses the warm cache and never hits the
  network (fails clearly if the cache is cold).
- **Vendor mode.** `vendor.set(true)` writes the maps into the source tree
  (`src/main/resources/maps`) instead of a generated dir, for fully committed /
  air-gapped builds. In vendor mode the fetch is **not** wired into the build:
  run `./gradlew fetchRosettaMaps` once, commit the result, and ordinary builds
  use the committed files with no network.

## Schema-version gate

Maps are fetched, then filtered against the resolver's `CURRENT_SCHEMA_VERSION`
before bundling: a map whose `schema_version` this client wouldn't accept is
**skipped** (logged), never baked into an artifact whose `MapLoader` would reject
it at runtime. Maps at the current version are additionally validated with the
real `MapLoader`, so a corrupt map fails the build loudly rather than on-device.

## Configuration reference

| Property          | Default                          | Meaning                                                            |
| ----------------- | -------------------------------- | ------------------------------------------------------------------ |
| `app`             | — (required)                     | App package whose maps to fetch.                                   |
| `ref`             | — (required)                     | rosetta-maps commit SHA / tag / branch to pin.                     |
| `versions`        | empty = all                      | Version codes to fetch, e.g. `listOf(8080L, 8081L)`.               |
| `repo`            | `Xiddoc/rosetta-maps`            | `owner/name` of the maps repo.                                     |
| `offline`         | `false`                          | Reuse the cache without the network.                               |
| `vendor`          | `false`                          | Write into the source tree instead of a generated dir.            |
| `outputDirectory` | `build/generated/rosetta-maps`   | Generated resources root (maps land in its `maps/` subdir).        |
| `vendorDirectory` | `src/main/resources`             | Vendor-mode resources root.                                        |
| `cacheDirectory`  | `<gradleUserHome>/rosetta-maps-cache` | Ref-keyed fetch cache.                                        |
