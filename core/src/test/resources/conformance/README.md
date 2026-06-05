# Rosetta resolver conformance fixtures

These JSON files are the **cross-client parity oracle** for resolution
semantics. They are deliberately **provider-neutral**: the same files are
intended to drive both the Kotlin resolver (rosetta-xposed `:core`,
`ConformanceTest.kt`) and the TypeScript resolver (rosetta-frida) so the
two implementations stay behaviour-identical (RFC 0001, Decision 1 & 2).

A faithful runner on either side should be implementable from this README
alone. **Keep every case strictly about resolution.** No signer / identity
verification, no Frida- or Xposed-specific fields, no language-specific
assertions.

## File shape

```jsonc
{
  "description": "human-readable note about what this file covers",
  "map": { /* a schema_version:2 RosettaMap, optional — see below */ },
  "cases": [ /* one or more case objects */ ]
}
```

- **`description`** — free text, ignored by the runner.
- **`map`** — an inline `schema_version: 2` map (same shape consumed by
  both clients). It is loaded and validated, then a single `Resolver` is
  built from it and shared by every case in the file. It is **optional**:
  a file whose cases are all pure utilities (`parseSignatureArgs`) needs
  no map. Any case that touches the resolver in a file with no `map` is a
  fixture-authoring error.
- **`cases`** — array of case objects (below).

## Case object

Every case has:

- **`name`** (string) — unique-ish label, used in the test report.
- **`kind`** (string) — which resolver operation to exercise (below).
- Either an **`expect*`** field describing the expected result, **or**
  **`expectError`** naming the expected failure. A case has exactly one of
  these two outcomes.

### `kind` values and their inputs

| `kind`               | inputs                              | success expectation                                  |
| -------------------- | ----------------------------------- | ---------------------------------------------------- |
| `class`              | `class`                             | `expectObf`, optional `expectExtends`                |
| `method`             | `class`, `method`, optional `argTypes` | `expectObf`, optional `expectSignature`, `expectClassName`, `expectStatic`, `expectAidlTxn`, `expectOverloadCount` |
| `field`              | `class`, `field`                    | `expectObf`, optional `expectStatic`, `expectType`, `expectClassName` |
| `hasClass`           | `class`                             | `expectResult` (boolean)                             |
| `reverseLookup`      | `obf`                               | `expectResult` (string FQN, or JSON `null`)          |
| `translateType`      | `type`                              | `expectResult` (string)                              |
| `toJvmDescriptor`    | `type`                              | `expectResult` (string descriptor)                   |
| `parseSignatureArgs` | `signature`                         | `expectList` (array of descriptor strings)           |

Notes on inputs:

- **`class`** — a class **real name** (FQN like `com.example.app.Foo`, an
  inner class `com.example.app.Outer$Inner`, or a short name `IFoo`).
- **`method`** / **`field`** — a method/field **real name**.
- **`argTypes`** — array of argument **type names** used to disambiguate
  overloads. May be:
  - omitted entirely → resolver auto-picks the lone overload, or throws
    `AmbiguousOverload` if there are several;
  - `[]` → matches the zero-arg overload;
  - real class names / framework class names (`android.os.Bundle`),
    translated through the map (real → obf) before matching;
  - primitive names (`int`, `boolean`, …);
  - array forms (`int[]`, `java.lang.String[][]`,
    `com.example.app.Outer$Inner[]`);
  - raw JVM descriptors as an escape hatch (`Lbbbb;`, `[B`, lone `J`),
    passed through unchanged.
- **`type`** — a single bare type name for `translateType` /
  `toJvmDescriptor`. `translateType` takes a bare class name (no wrappers)
  and returns a bare name; `toJvmDescriptor` takes any type name (incl.
  arrays / primitives / raw descriptors) and returns a JVM descriptor.
- **`obf`** — an obfuscated class **short name** for `reverseLookup`.
- **`signature`** — a JVM method descriptor for `parseSignatureArgs`.

### Expectation fields

- **`expectObf`** (string) — the resolved obfuscated short name.
- **`expectSignature`** (string) — the picked overload's JVM signature.
- **`expectClassName`** (string) — the obfuscated short name of the class
  the resolved method/field lives on.
- **`expectStatic`** (boolean) — resolved static flag (absent flag in the
  map ⇒ `false`).
- **`expectAidlTxn`** (number) — resolved AIDL transaction code.
- **`expectOverloadCount`** (number) — number of overloads carried in the
  result; the **selected** overload is always first.
- **`expectType`** (string) — resolved field JVM-descriptor type.
- **`expectExtends`** (string | `null`) — the class entry's `extends`.
- **`expectResult`** (string | boolean | `null`) — generic single result
  for `hasClass` / `reverseLookup` / `translateType` / `toJvmDescriptor`.
  A JSON `null` asserts an absent / null result (e.g. `reverseLookup`
  miss).
- **`expectList`** (array of strings) — for `parseSignatureArgs`.

Any `expect*` field that is **absent** is simply not asserted, so a case
can assert as little or as much as it wants. A field present with value
`null` is asserted to be null (it is **not** the same as absent).

### `expectError` values

| value               | meaning                                                                   |
| ------------------- | ------------------------------------------------------------------------- |
| `Resolve`           | a real name could not be resolved (missing class/method/field, or no overload matched the given `argTypes`). |
| `AmbiguousOverload` | a method has several overloads and no `argTypes` were supplied.           |
| `IllegalArgument`   | a malformed input to a signature utility (bad signature / empty type name). |

Each client maps these to its own error type (Kotlin: `ResolveException`,
`AmbiguousOverloadException`, `IllegalArgumentException`; TS: the
corresponding `ResolveError` / `AmbiguousOverloadError` / thrown
`Error`/`RangeError` from the signature helpers). The **taxonomy** is what
is asserted, not the message text.

## Resolution semantics captured here (summary)

- **Class**: exact real-name lookup; inner (`$`) and short names are just
  keys; a miss throws `Resolve`. `hasClass` is the boolean form.
- **Method, single overload**: auto-picked when `argTypes` is omitted; if
  `argTypes` are given they must still match (a non-match is a `Resolve`
  miss, even for a lone overload).
- **Method, multiple overloads**: `argTypes` disambiguate by translating
  each arg to a JVM descriptor (real → obf via the map) and comparing the
  full descriptor list to each overload's parsed signature args. Matching
  is by **exact descriptor equality** — no supertype widening. No
  `argTypes` ⇒ `AmbiguousOverload`; no matching overload ⇒ `Resolve`.
- **Field**: exact real-name lookup; static/instance and type propagate; a
  miss throws `Resolve`.
- **Type translation**: a mapped real name → its obf short name; anything
  else (framework class, primitive, unknown class) passes through.
- **Descriptor conversion**: primitives, arrays (incl. multi-dim), object
  classes (translated), and raw-descriptor / lone-primitive-letter
  passthroughs.
- **Signature parsing**: splits a descriptor's arg list, supporting
  primitives, object types, and (multi-dim) arrays; malformed signatures
  throw.

## Adding a fixture file

1. Drop a new `*.json` here following the shape above.
2. Register it in the runner's fixture manifest (Kotlin:
   `ConformanceTest.fixtures`; the TS runner uses its own import glob).
3. Keep it provider-neutral and resolution-only.
