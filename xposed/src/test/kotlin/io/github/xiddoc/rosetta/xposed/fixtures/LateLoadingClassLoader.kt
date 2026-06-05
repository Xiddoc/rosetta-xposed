/*
 * A class loader that mimics a packed / hardened app's late dex: it refuses to
 * resolve a configured set of obfuscated class names — throwing
 * ClassNotFoundException — until [release] is called, after which it delegates
 * to its parent (the real test classpath) like normal. This lets the deferred-
 * binding tests model "class not present yet → becomes present later" with no
 * real threads, sleeps, or device.
 */
package io.github.xiddoc.rosetta.xposed.fixtures

class LateLoadingClassLoader(
    parent: ClassLoader,
    private val gated: Set<String>,
) : ClassLoader(parent) {
    @Volatile
    private var released = false

    /** Count of load attempts for gated names — asserted to prove "never probe-loaded". */
    @Volatile
    var loadAttempts: Int = 0
        private set

    /** Make the gated classes resolvable from now on. */
    fun release() {
        released = true
    }

    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (name in gated) {
            loadAttempts++
            if (!released) throw ClassNotFoundException(name)
        }
        return super.loadClass(name, resolve)
    }
}
