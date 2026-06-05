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
| `validate`           | `inputMap`                          | `expectValid: true`, **or** `expectError: "MapValidation"` |

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
- **`inputMap`** — for the `validate` kind ONLY: an inline, self-contained
  map the client runs through its own validator (TS: `validateMap`; Kotlin:
  `MapLoader.validate`). Unlike the file-level `map` (loaded once and shared
  by resolution cases), each `validate` case carries its OWN `inputMap` and
  asserts the validator's verdict — not a resolution result. This is how the
  oracle covers VALIDATION semantics (the schema/bounds gate) in addition to
  resolution semantics. A `validate` case asserts either `expectValid: true`
  (the map must pass) or `expectError: "MapValidation"` (the map must be
  rejected — e.g. an empty `obfuscated`, violating `minLength: 1`).

### Expectation fields

- **`expectObf`** (string) — the resolved obfuscated short name.
- **`expectSignature`** (string) — the picked overload's JVM signature.
- **`expectClassName`** (string) — the obfuscated short name of the class
  the resolved method/field lives on. The value is the class's `obfuscated`
  token verbatim (the obfuscated SHORT name, e.g. `aaaa` or `aaaa$Inner`),
  NOT a fully-qualified obfuscated FQN — a TS-runner author should mirror
  this exact form.
- **`expectStatic`** (boolean) — resolved static flag (absent flag in the
  map ⇒ `false`).

  > **`Boolean?` null-projection (parity note).** The resolved `static` /
  > `synthetic` / `isConstructor` flags are tri-state on the Kotlin side
  > (`Boolean?`, where `null` = "the map didn't say"), whereas the TS side
  > folds them to a plain `boolean`. The Kotlin conformance runner therefore
  > folds the resolved value via `== true` before comparing to the fixture's
  > boolean; the TS runner compares the boolean directly. So **fixtures must
  > assert only `true` or `false` for these flags, never `null`** — a `null`
  > expectation has no consistent meaning across the two runners. (An absent
  > flag in the source map projects to `false`.)
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
- **`expectValid`** (boolean) — for the `validate` kind: `true` asserts the
  `inputMap` passes validation. (Rejection is asserted via `expectError:
  "MapValidation"` instead.)

Any `expect*` field that is **absent** is simply not asserted, so a case
can assert as little or as much as it wants. A field present with value
`null` is asserted to be null (it is **not** the same as absent).

### `expectError` values

| value               | meaning                                                                   |
| ------------------- | ------------------------------------------------------------------------- |
| `Resolve`           | a real name could not be resolved (missing class/method/field, or no overload matched the given `argTypes`). |
| `UnknownArgType`    | overload disambiguation failed specifically because an `argTypes` entry is a class-name form the map does not know AND no overload declares its translated descriptor. A **distinct subtype** of the `Resolve` family, asserted precisely. |
| `AmbiguousOverload` | a method has several overloads and no `argTypes` were supplied.           |
| `IllegalArgument`   | a malformed input to a signature utility (bad signature / empty type name / unknown descriptor char). |
| `MapValidation`     | for the `validate` kind: the `inputMap` was rejected by the schema/bounds validator (TS: `MapValidationError`; Kotlin: `MapValidationException`). |

Each client maps these to its own error type (Kotlin: `ResolveException`,
`UnknownArgTypeException`, `AmbiguousOverloadException`,
`IllegalArgumentException`; TS: the corresponding `ResolveError` /
`UnknownArgTypeError` / `AmbiguousOverloadError` / thrown `Error`/`RangeError`
from the signature helpers). The **taxonomy** is what is asserted, not the
message text.

`UnknownArgType` is a **subtype** of the `Resolve` family on both sides
(`UnknownArgTypeException extends ResolveException`; `UnknownArgTypeError
extends ResolveError`), so generic `Resolve`-error handling still catches
it. The conformance runners assert the precise subtype: a `Resolve` case is
asserted to be NOT the `UnknownArgType` subtype, and an `UnknownArgType`
case is asserted to be exactly that subtype.

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
