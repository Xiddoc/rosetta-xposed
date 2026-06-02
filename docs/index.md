# rosetta-xposed

**Hook obfuscated Android apps from Xposed / LSPosed / LSPatch modules
using real Java class, method, and field names.**

Large Android apps rotate their obfuscation every release, so Xposed hooks
written against hard-coded obfuscated names break on the next update.
`rosetta-xposed` loads a per-version map that translates real names to the
obfuscated names the running build actually uses, so a module can keep
referencing stable, real names across versions.

It is a **thin resolver, not a hook framework** (RFC 0001 Decision 2): it
resolves a real name to a `java.lang.reflect.Member` and hands it to your
chosen hook API — the modern libxposed API or legacy `XposedHelpers`. It
does **not** own the hook call. This mirrors rosetta-frida's "just makes
`Java.use` smarter" philosophy.

## Where it fits

rosetta-xposed is the JVM/Xposed **client** of the `schema_version: 2` map
format. That format is **owned** by
[rosetta-maps](https://github.com/Xiddoc/rosetta-maps)
(`schema/rosetta-map.schema.json` is the single source of truth);
[rosetta-frida](https://github.com/Xiddoc/rosetta-frida) is the other,
first-class client and the home of RFC 0001. This repo tracks that schema
from the Kotlin side and consumes the maps both clients share. See
[Related repos](reference/related.md) for the full picture.

## Status

This repo is **scaffolding**. The neutral core (model, loader, resolver,
conformance suite) and the static Xposed binding are implemented and tested
on the JVM; the DexKit dynamic backend, deferred binding, on-device app
identity, and Maven publishing are **planned**. See [Status](reference/status.md).

## Next steps

- [Concepts](getting-started/concepts.md) — the four-layer picture and the modules.
- [Usage](getting-started/usage.md) — resolve a real name and hook it.
- [Resolution backends](reference/backends.md) — static vs. dynamic.
- [Building](reference/building.md) — toolchain and Gradle tasks.
