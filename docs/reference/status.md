# Status

The neutral `:core` (model + loader + resolver + conformance suite), the
static and dynamic `:xposed` bindings, and the optional `:dexkit` adapter
are all implemented and tested on the JVM. What remains is on-device /
native wiring and Maven publishing.

## Built

- `:core` — `schema_version: 2` map model, strict JSON loader/validator,
  and resolver, kept in lockstep with the shared conformance suite.
- `:xposed` static path — `RosettaXposed`, the static resolution backend,
  bind targets, the `Hooker` seam, and `AppIdentity`.
- **`signer_sha256` enforcement** — fail-closed authenticity guard
  (`SignerGuard.verify`), enforced by `fromRegistry` and the
  identity-bearing `fromMap`. Opt-in per map (a map with no `signer_sha256`
  is not checked) but no opt-out once present. The map pins one expected
  hash; the app presents a SET (`AppIdentity.signerSha256s`) and the guard
  matches **any** member. Hashes are normalized (lowercase 64-hex, `:`
  stripped, surrounding whitespace trimmed); a malformed map hash throws
  `MalformedSignerException`. The unchecked path is `fromMapUnverified`.
- **Dynamic (self-healing) backend** — `DynamicResolutionBackend` discovers
  obfuscated names at runtime via signature strategies (AIDL descriptor,
  stable string anchors, superclass narrowing, method scan). Fully
  implemented and unit-tested with a `FakeDexKitIndex` on the plain JVM.
- **Composite backend** — `CompositeResolutionBackend` answers from the
  static map first and falls through to discovery on a miss; on success it
  writes back into the static resolver so subsequent lookups are O(1).
  Implemented and unit-tested.
- **Deferred binding** — `DeferredBinding` + `ClassAvailabilityWatcher`
  retry binding for classes that arrive in late-loaded dex. Implemented
  and unit-tested.
- **`:dexkit` optional module** — `DexKitBackedIndex` adapts the pure-JVM
  `DexKitIndex` seam to the real `DexKitBridge` native. Built and has an
  integration test that runs real DexKit against a committed obfuscated DEX
  fixture; the test skips automatically when the native `.so` is absent (CI
  builds it from pinned source and caches it).

## Remaining work

- **On-device native wiring** — end-to-end exercise of DexKit on a real
  Android device is not yet proven; the committed DEX fixture + CI native
  build cover the adapter path, but a physical-device run has not been
  validated.
- **`AppIdentity` from `PackageManager`** — the consuming module fills
  `AppIdentity`; selection and signer enforcement are built, but the
  convenience wiring from a real `PackageManager` lives in the consuming
  module, not here.
- **Maven publishing** — publishing to a Maven coordinate is pending.
