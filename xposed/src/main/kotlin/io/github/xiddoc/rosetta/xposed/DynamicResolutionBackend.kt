/*
 * Dynamic backend — on-device self-healing discovery (B.1, RFC 0001
 * Decision 2 / 5).
 *
 * This is the fallback used when no static map exists for the running
 * version_code: instead of an O(1) map lookup, it DISCOVERS the obfuscated
 * names live by signature, via the injected [DexKitIndex] seam, and records
 * the result through a [DiscoverySink] as a `rosetta-runtime-discovered`
 * source.
 *
 * The discovery LOGIC ships here and is fully unit-testable on a plain JVM (a
 * `FakeDexKitIndex` supplies canned answers); the real DexKit-backed adapter
 * that implements [DexKitIndex] on a device lives in the optional `:dexkit`
 * module (built and integration-tested against a committed DEX fixture; what
 * is not yet proven is end-to-end on-device wiring with the native loaded on
 * Android).
 *
 * STRATEGY ORDER (most stable signal first; first hit wins):
 *
 *   (a) AIDL descriptor   — the stable cross-version anchor for binder stubs.
 *   (b) stable string anchors — literals the class references, RE2-validated.
 *   (b′) regex string anchors — string CONSTANTS matched by RE2 regex (the
 *       genuine-regex form of (b); routed through [SafePattern.compileAll]).
 *   (c) superclass / extends narrowing — find a class by its (obf) parent.
 *   (d) method signature scan within a found class — only after the class is
 *       located, so the scan has a known starting point.
 *   (e) kept-name member harvest — once the class is located, enumerate its
 *       members and key each by its own (obfuscated) short name. For a method
 *       whose name R8 KEPT (an app's "kept carve-out" — e.g. GreenDAO entity
 *       accessors that don't rotate), that key IS the real name, so the method
 *       resolves with no per-method signature at all (#47). A renamed
 *       member is keyed by its non-real obf name (inert — never looked up by a
 *       real name); only strategy (d) maps those.
 *
 * SECURITY / FAIL-CLOSED. Every contributor pattern flows through
 * [SafePattern] (bounds-then-RE2). A class miss or an over-bound input throws
 * [DiscoveryException] and records NOTHING — the cache is never poisoned with a
 * half entry. A per-method hint that finds nothing (strategy d) is SKIPPED, not
 * fatal (#48): a single un-findable helper signature must not sink the
 * whole class, so the failure surfaces fail-closed only if THAT method is
 * requested and neither (d) nor the kept-name harvest (e) produced it. Only
 * members an index query actually matched are ever written back — never a
 * fabricated or guessed one. The discovered obfuscated FQN is NOT trusted
 * blindly: the binding layer routes it through the same C1 target guard as a
 * static name (see [RosettaXposed] / [TargetLoader]); this backend only
 * produces the name.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.AmbiguousOverloadException
import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.MethodEntry
import io.github.xiddoc.rosetta.core.model.MethodOverloads
import io.github.xiddoc.rosetta.core.resolver.DiscoveredClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedClass
import io.github.xiddoc.rosetta.core.resolver.ResolvedField
import io.github.xiddoc.rosetta.core.resolver.ResolvedMethod
import io.github.xiddoc.rosetta.core.resolver.parseSignatureArgs
import io.github.xiddoc.rosetta.core.resolver.toJvmDescriptor

/**
 * Per-method discovery facets — how to find ONE method's obfuscated name by
 * signature once its declaring class has been located. All facets are
 * optional; an empty hint scans by name alone (rarely unique).
 *
 * @property realName the real method name this hint discovers.
 * @property descriptor JVM descriptor of the method (obfuscated class refs).
 * @property returnType obfuscated FQN / descriptor of the return type.
 * @property paramTypes obfuscated FQNs / descriptors of the parameters.
 * @property usingStrings stable string literals the method references
 *   (contributor input; RE2-bounded via [SafePattern] before use).
 */
public data class MethodDiscoveryHint(
    val realName: String,
    val descriptor: String? = null,
    val returnType: String? = null,
    val paramTypes: List<String>? = null,
    val usingStrings: List<String> = emptyList(),
)

/**
 * The contributor-supplied recipe for discovering ONE real class at runtime.
 * At least one of the class-locating facets ([aidlDescriptor], [anchors],
 * [superclass]) must be present for the backend to attempt discovery.
 *
 * @property aidlDescriptor stable AIDL interface descriptor (strategy a).
 * @property anchors stable string literals the class references (strategy b);
 *   contributor input, RE2-bounded via [SafePattern].
 * @property regexAnchors stable string CONSTANTS the class references, matched
 *   as RE2 regexes (strategy b′) — the genuine-regex form of [anchors] (e.g.
 *   an endpoint URL with a `.*` wildcard). Each is RE2-compiled and bounded
 *   via [SafePattern.compileAll] before use, then matched on-device with
 *   `StringMatchType.SimilarRegex`.
 * @property superclass obfuscated FQN of the class's superclass (strategy c).
 * @property methods per-method discovery hints (strategy d), keyed nowhere —
 *   carried as a list and matched by [MethodDiscoveryHint.realName].
 */
public data class DiscoveryHints(
    val aidlDescriptor: String? = null,
    val anchors: List<String> = emptyList(),
    val regexAnchors: List<String> = emptyList(),
    val superclass: String? = null,
    val methods: List<MethodDiscoveryHint> = emptyList(),
) {
    /** True when at least one class-locating strategy could run. */
    public val canLocateClass: Boolean
        get() = aidlDescriptor != null || anchors.isNotEmpty() || regexAnchors.isNotEmpty() || superclass != null
}

/**
 * The contributor-supplied discovery configuration: the per-real-class recipes
 * plus where to record discoveries. Bundled so the [RosettaXposed] factory
 * keeps a small parameter list.
 *
 * @property hints the per-real-class discovery recipes (contributor input).
 * @property sink where discovered entries are recorded (default: NOOP).
 * @property cache the persistence seam consulted before scanning and written
 *   after a successful discovery, so a discovery survives a process restart
 *   (default: NOOP — no persistence, rosetta-xposed#19).
 * @property observer the observability side-channel reporting whether each
 *   discovery was a fresh scan or a cache hit (default: NOOP,
 *   rosetta-xposed#22). A pure side-channel — it never changes resolution.
 */
public data class DiscoveryConfig(
    val hints: Map<String, DiscoveryHints> = emptyMap(),
    val sink: DiscoverySink = DiscoverySink.NOOP,
    val cache: DiscoveryCache = DiscoveryCache.NOOP,
    val observer: DiscoveryObserver = DiscoveryObserver.NOOP,
)

/**
 * The DexKit-backed dynamic backend. Runs the discovery strategy order for a
 * real name using [index], records successes via [sink], and memoizes them so
 * a repeat lookup never re-scans.
 *
 * @property index the device-side index seam (faked in tests).
 * @property hints the per-real-class discovery recipes (contributor input).
 * @property sink where discovered entries are recorded (default: NOOP).
 * @property translateType translates a caller-supplied `argTypes` entry from a
 *   real name to its obfuscated short name (a primitive / unmapped framework
 *   type passes through). It MUST be the SAME translation the static
 *   [Resolver][io.github.xiddoc.rosetta.core.resolver.Resolver] applies, so a
 *   mapped app-class arg type matches a discovered overload whose descriptor
 *   already carries the obfuscated ref. Defaults to identity (no map context),
 *   which is correct only when callers pass framework types / raw descriptors;
 *   [RosettaXposed.fromMapWithDiscovery] wires the static map's translator in.
 * @property cache the cross-restart persistence seam (rosetta-xposed#19),
 *   consulted on the first miss for a real name and written after a successful
 *   discovery. Defaults to [DiscoveryCache.NOOP] (no persistence); the
 *   in-process memo still serves repeats within one run.
 * @property observer the observability side-channel (rosetta-xposed#22):
 *   notified [DiscoveryOutcome.DISCOVERED] on a fresh scan and
 *   [DiscoveryOutcome.SERVED_FROM_CACHE] on a persistent-cache hit, exactly
 *   ONCE per real name per process (the in-process memo is transparent). It
 *   never affects resolution and never throws through (see [DiscoveryObserver]).
 *   Defaults to [DiscoveryObserver.NOOP].
 */
public class DynamicResolutionBackend(
    private val index: DexKitIndex,
    private val hints: Map<String, DiscoveryHints>,
    private val sink: DiscoverySink = DiscoverySink.NOOP,
    private val translateType: (String) -> String = { it },
    private val cache: DiscoveryCache = DiscoveryCache.NOOP,
    private val observer: DiscoveryObserver = DiscoveryObserver.NOOP,
) : DiscoveringBackend {
    /** Memoized discovered class entries, so we scan a real name at most once. */
    private val discovered = mutableMapOf<String, ClassEntry>()

    /**
     * True when a class-locating strategy could run for [realClass] — i.e. a
     * hint exists with at least one of (aidl, anchors, superclass). It does
     * NOT promise the scan will hit; an actual miss fails closed at resolve.
     */
    override fun canResolve(realClass: String): Boolean = hints[realClass]?.canLocateClass == true

    override fun resolveClass(realClass: String): ResolvedClass {
        val entry = discoverClassEntry(realClass)
        return ResolvedClass(realName = realClass, obfName = entry.obfuscated, extends = entry.extends)
    }

    /**
     * Discover [realClass] and surface the typed [DiscoveredClass] write-back
     * payload the composite heals into the static backend. Carries only the
     * resolver-relevant fields (obf name, extends, methods, fields); the full
     * provenance entry is recorded separately through the [DiscoverySink].
     */
    override fun discoverClass(realClass: String): DiscoveredClass {
        val entry = discoverClassEntry(realClass)
        return DiscoveredClass(
            realName = realClass,
            obfName = entry.obfuscated,
            extends = entry.extends,
            methods = entry.methods,
            fields = entry.fields,
        )
    }

    override fun resolveMethod(
        realClass: String,
        realMethod: String,
        argTypes: List<String>?,
    ): ResolvedMethod {
        val classEntry = discoverClassEntry(realClass)
        // The class entry's methods come from BOTH a per-method signature scan
        // (strategy d, keyed by real name; SEVERAL overloads may share one name —
        // xposed#14 M18) AND the kept-name member harvest (strategy e, a kept
        // method's obf short name IS its real name). A name absent from both is
        // the only miss case — fail closed.
        val overloads =
            classEntry.methods?.get(realMethod)
                ?: throw DiscoveryException(
                    "rosetta-xposed: dynamic discovery resolved class '$realClass' to " +
                        "'${classEntry.obfuscated}' but found no method '$realMethod' on it — neither a " +
                        "signature hint nor a kept (unrenamed) member of that name matched (fail-closed; " +
                        "the cache is not poisoned). A RENAMED, stringless method needs a richer signature.",
                )
        val entries = overloads.entries
        // Select the overload the SAME way the static Resolver does: with
        // argTypes, match the requested descriptor; without, require exactly one.
        // The caller passes REAL names (+ framework types); discovered
        // descriptors carry the OBFUSCATED class refs, so each arg type is
        // translated real → obf through [translateType] — exactly the static
        // resolver's translation — so a mapped app-class arg type matches. A
        // mismatch / ambiguity fails closed rather than silently picking wrong.
        val methodEntry =
            if (argTypes == null) {
                entries.singleOrNull()
                    ?: throw AmbiguousOverloadException(
                        "rosetta-xposed: dynamic discovery found ${entries.size} overloads of " +
                            "'$realClass.$realMethod' — pass argTypes to disambiguate.",
                        realMethod,
                        realClass,
                        entries.size,
                    )
            } else {
                val wanted = argTypes.map { toJvmDescriptor(it) { name -> translateType(name) } }
                entries.firstOrNull { parseSignatureArgs(it.signature) == wanted }
                    ?: throw DiscoveryException(
                        "rosetta-xposed: no discovered overload of '$realClass.$realMethod' matches the " +
                            "requested arg types [${argTypes.joinToString(", ")}] " +
                            "(discovered signatures: ${entries.joinToString(", ") { it.signature }}).",
                    )
            }
        // Selected entry first so consumers can read allOverloads[0] safely,
        // mirroring the static Resolver's ordering.
        val ordered = listOf(methodEntry) + entries.filter { it !== methodEntry }
        return ResolvedMethod(
            realName = realMethod,
            obfName = methodEntry.obfuscated,
            className = classEntry.obfuscated,
            signature = methodEntry.signature,
            static = methodEntry.static,
            synthetic = methodEntry.synthetic,
            isConstructor = methodEntry.isConstructor,
            allOverloads = ordered,
        )
    }

    override fun resolveField(
        realClass: String,
        realField: String,
    ): ResolvedField =
        // Field discovery by signature is not part of the B.1 strategy set
        // (DexKit field queries land with the device adapter), so this fails
        // closed rather than fabricate a field mapping.
        //
        // xposed#14 L2: the previous `discoverClassEntry(realClass)` call here
        // was dead — its only effect was a class discovery + provenance write as
        // a side effect of a doomed field lookup, which surprises callers (a
        // FAILED resolveField mutating the discovery sink) and is not how a
        // resolver should behave. It was removed; field resolution now throws
        // immediately without scanning. The composite backend already discovers
        // the class on the class/method paths, so no provenance is lost.
        throw DiscoveryException(
            "rosetta-xposed: dynamic discovery does not resolve fields (real '$realClass.$realField'); " +
                "ship a static map entry or a future field-discovery hint.",
        )

    /**
     * Discover (or return the memoized) [ClassEntry] for [realClass]. Runs the
     * strategy order, then discovers the hinted methods within the found
     * class. Records the complete entry via [sink] exactly once. Fails closed
     * on any miss or partial result.
     */
    private fun discoverClassEntry(realClass: String): ClassEntry {
        discovered[realClass]?.let { return it }

        // A discovery persisted by a prior process short-circuits the expensive
        // DexKit scan (rosetta-xposed#19). Promote the cache hit into the
        // in-process memo so later lookups this run are O(1) too; the cache is
        // therefore consulted at most once per real name per process. A cache
        // hit is NOT re-emitted to the [sink]: the sink records what THIS run
        // freshly discovered (for upstream contribution), and a restored entry
        // was discovered in an earlier run.
        cache.get(realClass)?.let { cached ->
            discovered[realClass] = cached
            // Observability (#22): a relaunch served this name from the
            // persistent cache — no DexKit scan ran. Emitted once per name per
            // process (the memo above serves later lookups silently).
            DiscoveryObserver.safe(observer) {
                it.onOutcome(realClass, cached.obfuscated, DiscoveryOutcome.SERVED_FROM_CACHE)
            }
            return cached
        }

        val hint =
            hints[realClass]
                ?: throw DiscoveryException(
                    "rosetta-xposed: no discovery hint for real name '$realClass' — the dynamic " +
                        "backend has nothing to search by.",
                )

        val obfClass =
            locateClass(hint)
                ?: throw DiscoveryException(
                    "rosetta-xposed: dynamic discovery found no class for real name '$realClass' " +
                        "(tried AIDL descriptor / anchors / superclass).",
                )

        // Methods come from two complementary sources, merged (strategy e wins
        // nothing the hint already mapped):
        //   • HINTED scan (d) — a string / descriptor signature locates a method
        //     even when R8 RENAMED it (real → obf), keyed by real name. A hint
        //     that finds nothing is SKIPPED, not fatal (#48).
        //   • KEPT-NAME harvest (e) — every member on the located class, keyed by
        //     its own obf short name. For a kept (unrenamed) method that key IS
        //     the real name, so it resolves with no per-method signature
        //     (#47 — the TickTick `User#isPro` / GreenDAO carve-out case).
        // Hinted entries win on a key collision: an explicit real → obf mapping
        // is authoritative over the kept-name identity. A null result (both
        // empty) is a class-only discovery — the prior contract the write-back /
        // cache rely on.
        val merged = linkedMapOf<String, MethodOverloads>()
        merged.putAll(harvestKeptNameMethods(obfClass))
        merged.putAll(discoverHintedMethods(obfClass, hint.methods))
        val methods = if (merged.isEmpty()) null else merged

        // The synthesized map ClassEntry carries only the pure real→obf mapping
        // fields the schema_version: 5 model still has (obfuscated / extends /
        // methods / source). The hint's aidlDescriptor / anchors are runtime
        // DISCOVERY EVIDENCE used by locateClass(...) to FIND the class — they
        // are not map fields, so they are deliberately not round-tripped here.
        val entry =
            ClassEntry(
                obfuscated = obfClass,
                extends = hint.superclass,
                methods = methods,
                source = RUNTIME_DISCOVERED_TOOL,
            )

        // Memoize + persist + record only AFTER the full entry resolved
        // successfully, so a partial discovery (handled by the throws above)
        // never lands here — the persistent cache is never poisoned with a half
        // entry, exactly like the in-process memo.
        discovered[realClass] = entry
        cache.put(realClass, entry)
        // Two distinct notifications: the sink records the resolved entry's
        // PROVENANCE (this name was runtime-discovered), while the observer
        // below reports which resolve PATH produced it (a fresh scan vs a cache
        // hit — and cache hits never reach the sink at all).
        sink.record(realClass, entry)
        // Observability (#22): a fresh DexKit scan located this name (the
        // expensive path the cache exists to amortise). Emitted once per name
        // per process, AFTER the entry fully resolved (a partial discovery threw
        // above and never reaches here, so we never report a half result).
        DiscoveryObserver.safe(observer) {
            it.onOutcome(realClass, entry.obfuscated, DiscoveryOutcome.DISCOVERED)
        }
        return entry
    }

    /** Run strategies (a) → (c) in order; first hit wins. Null = a true miss. */
    private fun locateClass(hint: DiscoveryHints): String? {
        // (a) AIDL descriptor — the most stable cross-version anchor.
        hint.aidlDescriptor?.let { descriptor ->
            SafePattern.checkLen(descriptor)
            index.findClassByAidlDescriptor(descriptor)?.let { return it }
        }

        // (b) stable string anchors — RE2-bounded contributor input.
        if (hint.anchors.isNotEmpty()) {
            SafePattern.checkBounds(hint.anchors)
            index.findClassByAnchors(hint.anchors)?.let { return it }
        }

        // (b′) regex string anchors — string constants matched by RE2 regex.
        // SafePattern.compileAll enforces the bounds AND linear-time RE2 compile
        // (rejecting a malformed or pathological pattern fail-closed) before the
        // patterns reach the on-device SimilarRegex match.
        if (hint.regexAnchors.isNotEmpty()) {
            SafePattern.compileAll(hint.regexAnchors)
            index.findClassByStringPatterns(hint.regexAnchors)?.let { return it }
        }

        // (c) superclass / extends narrowing.
        hint.superclass?.let { superName ->
            SafePattern.checkLen(superName)
            index.findClassBySuperclass(superName)?.let { return it }
        }

        return null
    }

    /**
     * Strategy (d): for each hinted method, scan within [obfClass] for the
     * matching obfuscated method, keyed by the hint's REAL name. A hint that
     * finds nothing is SKIPPED, not fatal (#48): one un-findable helper
     * signature must not sink the whole class — the kept-name harvest (e) may
     * still cover that method, and if neither does, the miss surfaces
     * fail-closed only when that specific method is REQUESTED (see
     * [resolveMethod]). Returns an empty map when no hint resolved.
     *
     * OVERLOADS (xposed#14 M18). Several [MethodDiscoveryHint]s may share one
     * [MethodDiscoveryHint.realName] — overloads of the same method, each with a
     * distinct descriptor. They are ACCUMULATED under that name (keyed by
     * signature), not overwritten, so every discovered overload survives instead
     * of all but one being lost. A duplicate hint that rediscovers the SAME
     * signature is collapsed (idempotent), so a recipe listing the same overload
     * twice does not double-register it.
     *
     * The [SafePattern] bounds on a hint's descriptor / using-strings run BEFORE
     * the index query, so an over-bound contributor pattern still fails closed
     * (a malformed signature, distinct from a benign "method not found" skip).
     */
    private fun discoverHintedMethods(
        obfClass: String,
        methodHints: List<MethodDiscoveryHint>,
    ): Map<String, MethodOverloads> {
        // Preserve insertion order of both names and their overloads.
        val acc = linkedMapOf<String, MutableList<MethodEntry>>()
        for (mh in methodHints) {
            mh.descriptor?.let { SafePattern.checkLen(it) }
            SafePattern.checkBounds(mh.usingStrings)

            val match =
                index.findMethod(
                    MethodQuery(
                        declaringClass = obfClass,
                        descriptor = mh.descriptor,
                        returnType = mh.returnType,
                        paramTypes = mh.paramTypes,
                        usingStrings = mh.usingStrings,
                    ),
                ) ?: continue // resilient: a hint that finds nothing is skipped, not fatal (#48).
            accumulateOverload(acc, mh.realName, match)
        }
        return acc.mapValues { (_, entries) -> MethodOverloads(entries.toList()) }
    }

    /**
     * Strategy (e): enumerate every method DexKit reports on the located
     * [obfClass] and key each by its OWN obfuscated short name as a kept-name
     * identity (obfuscated == that name). For a method whose name R8 KEPT, that
     * key is the real name, so the method resolves with no per-method signature
     * — the on-device answer to an app's "kept carve-out" (#47; e.g.
     * TickTick's `com.ticktick.task.data.User#isPro()Z`, a stringless kept
     * GreenDAO accessor that no harvestable signature could pin). Members are
     * device FACTS from [DexKitIndex.membersOf], not contributor patterns, so
     * they carry no [SafePattern] bound; the obf CLASS name was already located
     * via a guarded strategy and is still routed through the C1 target guard
     * before any class load. Overloads accumulate; an exact (obf name,
     * signature) duplicate collapses. Empty when the class has no members (or
     * the index cannot enumerate it).
     */
    private fun harvestKeptNameMethods(obfClass: String): Map<String, MethodOverloads> {
        val acc = linkedMapOf<String, MutableList<MethodEntry>>()
        for (member in index.membersOf(obfClass)) {
            accumulateOverload(acc, member.obfName, member)
        }
        return acc.mapValues { (_, entries) -> MethodOverloads(entries.toList()) }
    }

    /**
     * Append [match] under [realName] in [acc], idempotent on (obf name,
     * signature): a repeat of the SAME overload registers once; genuinely
     * distinct overloads of the same name are all retained.
     */
    private fun accumulateOverload(
        acc: MutableMap<String, MutableList<MethodEntry>>,
        realName: String,
        match: MethodMatch,
    ) {
        val entries = acc.getOrPut(realName) { mutableListOf() }
        if (entries.none { it.obfuscated == match.obfName && it.signature == match.descriptor }) {
            entries += MethodEntry(obfuscated = match.obfName, signature = match.descriptor)
        }
    }
}
