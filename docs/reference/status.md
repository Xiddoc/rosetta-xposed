# Status

**Scaffolding.** The neutral `:core` (model + loader + resolver +
conformance suite) and the static `:xposed` binding (resolve → bind →
`Hooker`) are implemented and tested on the JVM.

## Built

- `:core` — `schema_version: 2` map model, strict JSON loader/validator,
  and resolver, kept in lockstep with the shared conformance suite.
- `:xposed` static path — `RosettaXposed`, the static resolution backend,
  bind targets, the `Hooker` seam, and `AppIdentity`.

## Planned (architected as skeletons, not yet built)

- the DexKit **dynamic backend** and **deferred binding** for late-loaded
  dex;
- on-device `signer_sha256` enforcement and `version_code` map selection
  wired to a real `PackageManager` read;
- publishing to a Maven coordinate.
