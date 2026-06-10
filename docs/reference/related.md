# Related repos

The Rosetta project spans three repos. rosetta-xposed is a **client** of a
schema it does not own.

## rosetta-maps — owns the schema

[rosetta-maps](https://github.com/Xiddoc/rosetta-maps) is the community
knowledge base of signatures and generated maps, and the **owner** of the
canonical, language-neutral map schema
(`schema/rosetta-map.schema.json`) — the single source of truth for the
`schema_version: 4` format. Changing the format means bumping that schema
first, then updating the client adapters.

The schema is **language-neutral JSON Schema**, so the Kotlin side consumes
it directly (or via codegen) rather than through any npm dependency. This
repo deliberately wires **no** cross-repo or git-URL dependency on it today;
that waits for a distribution phase.

## rosetta-frida — the other client

[rosetta-frida](https://github.com/Xiddoc/rosetta-frida) is the Frida
(layer-4) adapter. It is the other, first-class
client of the maps-owned schema (TypeScript/Zod side). rosetta-xposed is its
JVM twin: a faithful Kotlin port of the neutral core, kept honest by a
**shared conformance suite** so both clients resolve identically.

## How rosetta-xposed relates

- **Client, not owner.** It tracks the `schema_version: 4` format from the
  Kotlin side; `CURRENT_SCHEMA_VERSION` is hard-gated in `MapLoader`.
- **Consumer, not producer.** It loads maps; it does not author signatures
  or generate maps. Those live in rosetta-maps.
- **Resolver, not hook framework.** It makes a developer's existing hook
  API obfuscation-resilient; it does not define what a hook is.
