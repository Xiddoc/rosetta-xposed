# Resolution backends

A single resolution API (`ResolutionBackend`) sits in front of two
interchangeable backends.

## Static (default, built)

A pre-computed map exists for this `version_code`, so resolution is an O(1)
lookup: no DexKit, no native `.so`, no on-device scan. This is the backend
wired up today (`StaticResolutionBackend`).

## Dynamic (self-healing)

When no map exists for the running version, the dynamic backend
(`DynamicResolutionBackend`) discovers the obfuscated names live by
*signature*, emits a `rosetta-runtime-discovered` provenance source, and
(via the composite below) writes each discovery back so the next lookup is a
static hit.

### The injection seam (`DexKitIndex`)

The discovery **logic** ships now and is fully unit-testable on a plain JVM.
It runs behind a pure-JVM seam, `DexKitIndex`, whose signatures carry **no
DexKit types** — only plain value types (`MethodQuery` / `MethodMatch`) and
`String`s. That keeps `:xposed` device-free and lets tests inject a
`FakeDexKitIndex` with canned answers.

The real, device-only piece — a thin adapter that *implements* `DexKitIndex`
by translating these calls into `DexKitBridge` queries — is a deferred
follow-up. DexKit stays an **optional** later-phase dependency (it is the
only file that ever imports an `org.luckypray:dexkit` type) and is not in the
default build.

### Strategy order

Discovery runs the strategies most-stable-signal-first; the first hit wins:

1. **AIDL descriptor** — the stable cross-version anchor for binder stubs.
2. **Stable string anchors** — literals the class references.
3. **Superclass / `extends` narrowing** — find a class by its (obfuscated)
   parent.
4. **Method signature scan within a found class** — only after 1–3 have
   located the class, so the scan has a known starting point.

A class-only discovery (no method hints) is allowed; a hinted method that
isn't found is a **partial discovery** and fails closed.

### Fail-closed contract

Every abnormal outcome throws `DiscoveryException` and records **nothing** —
the cache is never poisoned with a half entry:

- a miss (no strategy hit);
- an over-bound contributor input (see ReDoS guard below);
- a partial discovery (class found, a hinted member not found);
- a field lookup (field discovery is not part of the B.1 strategy set).

### ReDoS guard (`SafePattern`)

Contributor-supplied patterns (signature descriptors, string anchors) are a
denial-of-service vector: the dynamic backend runs *inside* the target app,
so a catastrophic-backtracking pattern (e.g. `(a+)+$`) against a
backtracking engine could hang the host JVM. `SafePattern` is the single
chokepoint that neutralises this with two fail-closed layers:

1. **Bounds before compile.** `MAX_SIGNATURE_LEN = 4096` (per-string) and
   `MAX_ANCHORS = 1000` (per-list) — aligned with the schema caps
   (`MapLoader.MAX_SIGNATURE_LEN` / `MAX_ANCHORS_PER_CLASS`) — are enforced
   on the raw input **before** any compilation. Over-bounds throws
   `DiscoveryException`.
2. **Linear-time engine only.** Patterns compile through
   `com.google.re2j.Pattern` (RE2 — a linear-time automaton with no
   backtracking), never `java.util.regex` / `kotlin.text.Regex`. A
   pathological pattern returns promptly instead of hanging.

### Provenance (`DiscoverySink`)

A successful discovery is handed to a `DiscoverySink`. The in-memory
`MapDiscoverySink` records each entry and renders a
`MapSource(tool = "rosetta-runtime-discovered", confidence = LOW, …)` so a
discovered name is never mistaken for a vetted, high-confidence static
mapping — the same provenance the Frida side emits. (A persistent/on-device
cache and an upstream-contribution path are interface-only for now.)

## Composite (static-first, dynamic-on-miss)

`CompositeResolutionBackend` is what a self-healing module wires up via
`RosettaXposed.fromMapWithDiscovery(map, index, classLoader, identity?,
hints, sink, policy)`. It answers from the static map when it can, and only
falls through to discovery on a static miss. On a successful discovery it
writes the resolved `ClassEntry` back into the static resolver
(`Resolver.override`), so the **next** lookup of that real name is an O(1)
static hit and the index is consulted at most once per real class.

### Security: discovery runs after the signer guard, and is guarded by C1

- **After the signer guard.** When `fromMapWithDiscovery` is given an
  `AppIdentity`, the map's `signer_sha256` guard is enforced fail-closed
  *first*, so only a trusted process ever reaches discovery. (Pass `null`
  only when no identity is available — the unverified path.)
- **Guarded by C1.** A discovered obfuscated FQN is **not** trusted blindly.
  Every target — discovered or static — is realised through the same
  `TargetLoader.loadGuardedClass` chokepoint, so the C1 namespace guard
  rejects a discovery result that lands on a reserved namespace (e.g.
  `java.lang.Runtime`) with a `TargetPolicyException` *before* any class load
  or `setAccessible`. A malicious discovery result cannot bypass the
  namespace policy.
