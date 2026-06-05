/*
 * FakeDexKitIndex — a canned-answer [DexKitIndex] for unit tests.
 *
 * It stands in for the real, device-only DexKit adapter so the entire
 * discovery LOGIC ([DynamicResolutionBackend] / [CompositeResolutionBackend])
 * is exercised on a plain JVM with NO `org.luckypray:dexkit` on the classpath.
 *
 * Every lookup is a map/predicate over pre-seeded answers, and every call is
 * COUNTED ([calls]) so tests can assert the self-healing write-back consults
 * the index at most once per real class.
 */
package io.github.xiddoc.rosetta.xposed

class FakeDexKitIndex(
    private val byAidl: Map<String, String> = emptyMap(),
    private val byAnchors: Map<List<String>, String> = emptyMap(),
    private val bySuper: Map<String, String> = emptyMap(),
    private val methods: Map<String, List<MethodMatch>> = emptyMap(),
) : DexKitIndex {
    /** Total number of index queries made (any method). */
    var calls: Int = 0
        private set

    override fun findClassByAidlDescriptor(descriptor: String): String? {
        calls++
        return byAidl[descriptor]
    }

    override fun findClassByAnchors(anchors: List<String>): String? {
        calls++
        return byAnchors[anchors]
    }

    override fun findClassBySuperclass(superName: String): String? {
        calls++
        return bySuper[superName]
    }

    override fun findMethod(query: MethodQuery): MethodMatch? {
        calls++
        val candidates = methods[query.declaringClass].orEmpty()
        // Match by descriptor when given; otherwise return the lone candidate.
        return if (query.descriptor != null) {
            candidates.firstOrNull { it.descriptor == query.descriptor }
        } else {
            candidates.singleOrNull()
        }
    }

    override fun membersOf(obfClass: String): List<MethodMatch> {
        calls++
        return methods[obfClass].orEmpty()
    }

    /** Test-only helper to inspect the seeded methods for an obf class. */
    fun seededMethods(obfClass: String): List<MethodMatch> = methods[obfClass].orEmpty()
}
