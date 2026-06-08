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
by translating these calls into `DexKitBridge` queries — lives in the
`:dexkit` module (`DexKitBackedIndex`). DexKit stays an **optional**
later-phase dependency (it is the only file that ever imports an
`org.luckypray:dexkit` type) and is not in the default build. See
[DexKit integration](dexkit-integration.md) for the adapter, its build wiring,
the native-library / supported-platform skip semantics, and how to refresh the
fixtures.

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
mapping — the same provenance the Frida side emits. (An upstream-contribution
path is interface-only for now.)

### Persistence (`DiscoveryCache`)

The dynamic backend's in-memory memo only lives for one process, so without a
durable store every app restart re-runs the DexKit scan from scratch. The
`DiscoveryCache` seam (rosetta-xposed#19) lets a discovered
`realName → ClassEntry` survive restarts: the backend consults it on the first
miss for a real name (a hit short-circuits the scan and is promoted into the
in-memory memo) and writes to it after a successful discovery. A cache hit is
**not** re-emitted to the `DiscoverySink` — the sink records only what *this*
run freshly discovered.

The seam is pure-JVM and tiny (`get` / `put` by real name), so `:core` and
`:xposed` stay `android.jar`-free and fully covered. The default is
`DiscoveryCache.NOOP` (today's behaviour); `InMemoryDiscoveryCache` is a
non-persistent reference impl. The on-device, `SharedPreferences`-backed
`PersistentDiscoveryCache` lives at the Android edge in `:xposed-android`,
where it is stamped with a `(app, version_code, signer)` fingerprint at
construction and **drops every cached entry when that fingerprint changes**
(an app update, a signer change, or a first run) — so a stale mapping can't
survive an update. The irreducible `SharedPreferences` object stays in the
consumer behind a ~3-line `KeyValueStore` adapter (see the example module's
`SharedPreferencesStore`), exactly like `AppIdentity` keeps the `PackageManager`
read at the edge. A restored FQN is still routed through the same C1 target
guard as a static name, so a tampered store cannot widen the trust surface.

Wire one in through `DiscoveryConfig.cache`:

```kotlin
val prefs = context.getSharedPreferences("rosetta_disc_cache", Context.MODE_PRIVATE)
val cache = PersistentDiscoveryCache.create(SharedPreferencesStore(prefs), identity)
val rosetta = RosettaXposed.fromMapWithDiscovery(
    map, index, lpparam.classLoader, identity,
    discovery = DiscoveryConfig(hints = hints, cache = cache),
)
```

### Observability (`DiscoveryObserver`)

The backend already distinguishes a fresh DexKit scan from a persistent-cache
hit — and the cache distinguishes a stale-cache drop from a warm relaunch — but
nothing **surfaced** which happened. The `DiscoveryObserver` seam
(rosetta-xposed#22) funnels those outcomes through one small, testable
side-channel instead of ad-hoc log strings:

- `onOutcome(realName, obfName, DISCOVERED)` — a static miss fell through to a
  live DexKit scan that located the name.
- `onOutcome(realName, obfName, SERVED_FROM_CACHE)` — a discovery written by an
  earlier process was read back; **no** scan ran this launch.
- `onCacheInvalidated(hadPriorFingerprint)` — `PersistentDiscoveryCache.create`
  dropped a stale cache because the app's `(app, version_code, signer)`
  fingerprint changed (the flag separates a real update from a first run).

Each outcome fires **once per real name per process** (the in-memory memo is
transparent), and emits are **fail-soft**: an observer that throws can never
break a resolve (`DiscoveryObserver.safe` swallows it). The default is
`DiscoveryObserver.NOOP`; `RecordingDiscoveryObserver` is the in-memory
reference impl used in tests. The on-device e2e (see
[DexKit integration](dexkit-integration.md#on-device-dynamic-path-e2e-android-e2eyml-rosetta-xposed22))
wraps a thin logcat observer that turns each outcome into a greppable marker —
but the SAME outcomes are exercised on a plain JVM, which is where the testable
value lives. Wire one in through `DiscoveryConfig.observer`.

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
