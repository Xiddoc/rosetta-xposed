# Building

The project is a two-module Gradle (Kotlin/JVM) build. It builds and tests
on a plain JVM — no Android SDK or emulator required.

```sh
./gradlew build          # compile + test both modules (JVM only)
./gradlew :core:test     # run the shared conformance suite
```

The build requests a **JDK 17** toolchain and auto-provisions it (foojay
resolver) if your machine only has a newer JDK. CI runs on JDK 17 and 21.

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
