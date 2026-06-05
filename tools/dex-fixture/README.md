# dex-fixture

Hermetic builder for the DexKit integration-test fixture DEX.

## What it produces

| Artifact | Location |
|---|---|
| `fixture.dex` | `dexkit/src/test/resources/dex/fixture.dex` |
| `fixture-mapping.json` | `dexkit/src/test/resources/dex/fixture-mapping.json` |

## Requirements

- JDK 17+ on `PATH` (tested with JDK 21; `javac` and `java` must be available)
- Network access to Google Maven (first run only, to download R8)
- `JAVA_HOME` set (standard JDK install sets this automatically)

No Android SDK required.

## How to rebuild

```bash
cd tools/dex-fixture
./build.sh
# Both artifacts are refreshed in dexkit/src/test/resources/dex/.
# Commit the updated files.
```

`build.sh` is idempotent: the R8 jar is only downloaded once (cached in `.build/`).

## R8 version

**8.5.35** — downloaded from `https://maven.google.com/com/android/tools/r8/8.5.35/r8-8.5.35.jar`

To update, edit `R8_VERSION` in `build.sh`.

## Fixture classes

| Real name | Obfuscated name | Purpose / DexKit strategy |
|---|---|---|
| `com.rosetta.dexfixture.AnchoredWidget` | `com.rosetta.dexfixture.a` | find-class-by-anchors (string `"rosetta-dexfixture-anchor-AnchoredWidget"`) |
| `com.rosetta.dexfixture.BaseHandler` | `com.rosetta.dexfixture.b` | base class |
| `com.rosetta.dexfixture.NetworkHandler` | `com.rosetta.dexfixture.c` | find-class-by-superclass; `process(String)→a` for find-method-by-signature |
| `com.rosetta.dexfixture.RemoteStub` | `com.rosetta.dexfixture.d` | find-class-by-aidl-descriptor (string `"com.rosetta.dexfixture.IRemote"`) |
| `com.rosetta.dexfixture.Main` | `com.rosetta.dexfixture.Main` | entry point (kept un-obfuscated) |

## Obfuscation notes

- Names are pinned via `seed.txt` + `-applymapping` so they are deterministic across R8 runs.
- `-dontoptimize` prevents R8 from inlining string literals, ensuring both anchor strings survive in the DEX.
- DEX magic: `64 65 78 0a 30 33 35 00` (`dex\n035\0`) — StandardDex, not CompactDex.
