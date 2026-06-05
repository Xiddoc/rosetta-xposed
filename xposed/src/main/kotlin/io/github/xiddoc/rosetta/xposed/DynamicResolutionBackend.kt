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
 * The discovery LOGIC ships now and is fully unit-testable on a plain JVM (a
 * `FakeDexKitIndex` supplies canned answers); the real DexKit-backed adapter
 * that implements [DexKitIndex] on a device is a thin, deferred follow-up
 * (DexKit stays an optional later-phase dependency).
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
 */
public data class DiscoveryConfig(
    val hints: Map<String, DiscoveryHints> = emptyMap(),
    val sink: DiscoverySink = DiscoverySink.NOOP,
)

/**
 * The DexKit-backed dynamic backend. Runs the discovery strategy order for a
 * real name using [index], records successes via [sink], and memoizes them so
 * a repeat lookup never re-scans.
 *
 * @property index the device-side index seam (faked in tests).
 * @property hints the per-real-class discovery recipes (contributor input).
 * @property sink where discovered entries are recorded (default: NOOP).
 */
public class DynamicResolutionBackend(
    private val index: DexKitIndex,
    private val hints: Map<String, DiscoveryHints>,
    private val sink: DiscoverySink = DiscoverySink.NOOP,
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
        // Discovery records exactly one overload per real method name, so the
        // first entry IS the resolved overload. A missing methods map or an
        // unhinted method name is the only miss case.
        val overloads =
            classEntry.methods?.get(realMethod)
                ?: throw DiscoveryException(
                    "rosetta-xposed: dynamic discovery resolved class '$realClass' to " +
                        "'${classEntry.obfuscated}' but found no method '$realMethod' on it " +
                        "(partial discovery fails closed; the cache is not poisoned).",
                )
        val methodEntry = overloads.entries.first()
        // Honour argTypes when supplied (Liskov parity with the static
        // Resolver / StaticResolutionBackend): the caller pinned a specific
        // overload, so the discovered overload's descriptor MUST match it.
        // Discovered descriptors already carry obfuscated class refs, so the
        // arg-type names are translated as-is (identity translate). A mismatch
        // fails closed rather than silently returning the wrong overload.
        if (argTypes != null) {
            val wanted = argTypes.map { toJvmDescriptor(it) { name -> name } }
            val actual = parseSignatureArgs(methodEntry.signature)
            if (actual != wanted) {
                throw DiscoveryException(
                    "rosetta-xposed: dynamic discovery resolved '$realClass.$realMethod' to a " +
                        "single overload '${methodEntry.signature}', but it does not match the " +
                        "requested arg types [${argTypes.joinToString(", ")}] " +
                        "(discovery records one overload per name; pass the matching arg types " +
                        "or omit them).",
                )
            }
        }
        return ResolvedMethod(
            realName = realMethod,
            obfName = methodEntry.obfuscated,
            className = classEntry.obfuscated,
            signature = methodEntry.signature,
            aidlTxn = methodEntry.aidlTxn,
            static = methodEntry.static,
            synthetic = methodEntry.synthetic,
            isConstructor = methodEntry.isConstructor,
            allOverloads = listOf(methodEntry),
        )
    }

    override fun resolveField(
        realClass: String,
        realField: String,
    ): ResolvedField {
        // Field discovery by signature is not part of the B.1 strategy set
        // (DexKit field queries land with the device adapter). Fail closed
        // rather than fabricate a field mapping.
        //
        // The discoverClassEntry call below is INTENTIONAL: even though we will
        // throw, it ensures the class is discovered and written back through
        // the composite's uniform sink path (memoisation + provenance emit)
        // before we surface the field miss. Do NOT remove it as a dead call.
        discoverClassEntry(realClass)
        throw DiscoveryException(
            "rosetta-xposed: dynamic discovery does not resolve fields (real '$realClass.$realField'); " +
                "ship a static map entry or a future field-discovery hint.",
        )
    }

    /**
     * Discover (or return the memoized) [ClassEntry] for [realClass]. Runs the
     * strategy order, then discovers the hinted methods within the found
     * class. Records the complete entry via [sink] exactly once. Fails closed
     * on any miss or partial result.
     */
    private fun discoverClassEntry(realClass: String): ClassEntry {
        discovered[realClass]?.let { return it }

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

        // Memoize + record only AFTER the full entry resolved successfully, so
        // a partial discovery (handled by the throws above) never lands here.
        discovered[realClass] = entry
        sink.record(realClass, entry)
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
     */
    private fun discoverMethods(
        obfClass: String,
        methodHints: List<MethodDiscoveryHint>,
    ): MutableMap<String, MethodOverloads>? {
        if (methodHints.isEmpty()) return null

        val out = mutableMapOf<String, MethodOverloads>()
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

            out[mh.realName] =
                MethodOverloads(
                    listOf(
                        MethodEntry(
                            obfuscated = match.obfName,
                            signature = match.descriptor,
                            aidlTxn = mh.aidlTxn,
                        ),
                    ),
                )
        }
        return out
    }
}
