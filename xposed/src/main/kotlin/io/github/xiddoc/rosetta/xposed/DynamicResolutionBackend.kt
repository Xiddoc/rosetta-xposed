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
 *   (c) superclass / extends narrowing — find a class by its (obf) parent.
 *   (d) method signature scan within a found class — only after (a)/(b)/(c)
 *       have located the class, so the scan has a known starting point.
 *
 * SECURITY / FAIL-CLOSED. Every contributor pattern flows through
 * [SafePattern] (bounds-then-RE2). A miss, an over-bound input, or a partial
 * discovery (class found, member not) throws [DiscoveryException] and records
 * NOTHING — the cache is never poisoned with a half entry. The discovered
 * obfuscated FQN is NOT trusted blindly: the binding layer routes it through
 * the same C1 target guard as a static name (see [RosettaXposed] /
 * [TargetLoader]); this backend only produces the name.
 */
package io.github.xiddoc.rosetta.xposed

import io.github.xiddoc.rosetta.core.AmbiguousOverloadException
import io.github.xiddoc.rosetta.core.model.ClassEntry
import io.github.xiddoc.rosetta.core.model.ClassKind
import io.github.xiddoc.rosetta.core.model.Confidence
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
 * @property aidlTxn AIDL transaction code to carry onto the resolved entry.
 */
public data class MethodDiscoveryHint(
    val realName: String,
    val descriptor: String? = null,
    val returnType: String? = null,
    val paramTypes: List<String>? = null,
    val usingStrings: List<String> = emptyList(),
    val aidlTxn: Int? = null,
)

/**
 * The contributor-supplied recipe for discovering ONE real class at runtime.
 * At least one of the class-locating facets ([aidlDescriptor], [anchors],
 * [superclass]) must be present for the backend to attempt discovery.
 *
 * @property aidlDescriptor stable AIDL interface descriptor (strategy a).
 * @property anchors stable string literals the class references (strategy b);
 *   contributor input, RE2-bounded via [SafePattern].
 * @property superclass obfuscated FQN of the class's superclass (strategy c).
 * @property methods per-method discovery hints (strategy d), keyed nowhere —
 *   carried as a list and matched by [MethodDiscoveryHint.realName].
 */
public data class DiscoveryHints(
    val aidlDescriptor: String? = null,
    val anchors: List<String> = emptyList(),
    val superclass: String? = null,
    val methods: List<MethodDiscoveryHint> = emptyList(),
) {
    /** True when at least one class-locating strategy could run. */
    public val canLocateClass: Boolean
        get() = aidlDescriptor != null || anchors.isNotEmpty() || superclass != null
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
        // Discovery may record SEVERAL overloads under one real method name
        // (xposed#14 M18), each with a distinct signature. A missing methods map
        // or an unhinted method name is the only miss case.
        val overloads =
            classEntry.methods?.get(realMethod)
                ?: throw DiscoveryException(
                    "rosetta-xposed: dynamic discovery resolved class '$realClass' to " +
                        "'${classEntry.obfuscated}' but found no method '$realMethod' on it " +
                        "(partial discovery fails closed; the cache is not poisoned).",
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
            aidlTxn = methodEntry.aidlTxn,
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

        val methods = discoverMethods(obfClass, hint.methods)

        val entry =
            ClassEntry(
                obfuscated = obfClass,
                extends = hint.superclass,
                // Preserve the fact the backend already knew: a descriptor-located
                // class is an AIDL binder stub, so the entry round-trips that kind
                // back upstream rather than losing it.
                kind = if (hint.aidlDescriptor != null) ClassKind.AIDL_STUB else null,
                aidlDescriptor = hint.aidlDescriptor,
                anchors = hint.anchors.ifEmpty { null },
                methods = methods,
                source = RUNTIME_DISCOVERED_TOOL,
                confidence = Confidence.LOW,
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

        // (c) superclass / extends narrowing.
        hint.superclass?.let { superName ->
            SafePattern.checkLen(superName)
            index.findClassBySuperclass(superName)?.let { return it }
        }

        return null
    }

    /**
     * Strategy (d): for each hinted method, scan within [obfClass] for the
     * matching obfuscated method. A hint that finds nothing fails closed —
     * a class whose hinted members can't all be found is a partial discovery.
     * Returns null when there are no method hints (a class-only discovery).
     *
     * OVERLOADS (xposed#14 M18). Several [MethodDiscoveryHint]s may share one
     * [MethodDiscoveryHint.realName] — overloads of the same method, each with a
     * distinct descriptor. They are ACCUMULATED under that name (keyed by
     * signature), not overwritten, so every discovered overload survives instead
     * of all but one being lost. A duplicate hint that rediscovers the SAME
     * signature is collapsed (idempotent), so a recipe listing the same overload
     * twice does not double-register it.
     */
    private fun discoverMethods(
        obfClass: String,
        methodHints: List<MethodDiscoveryHint>,
    ): MutableMap<String, MethodOverloads>? {
        if (methodHints.isEmpty()) return null

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
                ) ?: throw DiscoveryException(
                    "rosetta-xposed: dynamic discovery located class '$obfClass' " +
                        "but found no method matching hint '${mh.realName}' " +
                        "(partial discovery fails closed).",
                )

            val entries = acc.getOrPut(mh.realName) { mutableListOf() }
            // Idempotent on (obf name, signature): a recipe that lists the same
            // overload twice registers it once; genuinely distinct overloads of
            // the same real name are all retained.
            if (entries.none { it.obfuscated == match.obfName && it.signature == match.descriptor }) {
                entries +=
                    MethodEntry(
                        obfuscated = match.obfName,
                        signature = match.descriptor,
                        aidlTxn = mh.aidlTxn,
                    )
            }
        }
        return acc.mapValuesTo(mutableMapOf()) { (_, entries) -> MethodOverloads(entries.toList()) }
    }
}
