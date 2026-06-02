# Resolution backends

A single resolution API (`ResolutionBackend`) sits in front of two
interchangeable backends (RFC 0001 Decision 2).

## Static (default, built)

A pre-computed map exists for this `version_code`, so resolution is an O(1)
lookup: no DexKit, no native `.so`, no on-device scan. This is the backend
wired up today (`StaticResolutionBackend`).

## Dynamic (self-healing, planned)

When no map exists for the running version, the dynamic backend runs
DexKit-dialect signatures on-device, resolves the names, emits a
`rosetta-runtime-discovered` source, caches the result, and optionally
contributes it upstream.

This is **architected now** (see `DynamicResolutionBackend`) but built in a
later phase; DexKit stays an **optional** dependency and is not in the
default build.
