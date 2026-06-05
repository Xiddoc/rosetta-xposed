# Status

**Scaffolding.** The neutral `:core` (model + loader + resolver +
conformance suite) and the static `:xposed` binding (resolve → bind →
`Hooker`) are implemented and tested on the JVM.

## Built

- `:core` — `schema_version: 2` map model, strict JSON loader/validator,
  and resolver, kept in lockstep with the shared conformance suite.
- `:xposed` static path — `RosettaXposed`, the static resolution backend,
  bind targets, the `Hooker` seam, and `AppIdentity`.
- **`signer_sha256` enforcement** — fail-closed authenticity guard
  (`SignerGuard` / `RosettaXposed.verifySigner`), enforced by
  `fromRegistry` and the identity-bearing `fromMap` overload. Opt-in per
  map (a map with no `signer_sha256` is not checked); hashes are normalized
  (lowercase hex, colons/whitespace stripped) before comparison.

## Planned (architected as skeletons, not yet built)

- the DexKit **dynamic backend** and **deferred binding** for late-loaded
  dex;
- wiring the `version_code` map selection to a real `PackageManager` read
  on-device (the consuming module fills `AppIdentity`; selection +
  signer enforcement themselves are built);
- publishing to a Maven coordinate.
