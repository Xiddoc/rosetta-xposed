/*
 * A test class loader that DEFINES a configured set of classes itself (reading
 * their bytecode from the parent's classpath) rather than delegating their load
 * to the parent. The point is the defining loader: a class loaded here has THIS
 * loader as its `Class.classLoader`, NOT the JVM system/platform loader — so it
 * models an ordinary app class realised by the app's own (non-platform) loader.
 *
 * This is what lets the xposed#12 walk-gate cover its "app-prefixed, NOT
 * allowlisted, and NOT platform-loaded → searchable" branch: every fixture on
 * the test classpath is otherwise realised by the system loader, which the
 * defense-in-depth loader check denies.
 */
package io.github.xiddoc.rosetta.xposed.fixtures

class LocalDefiningClassLoader(
    parent: ClassLoader,
    private val owned: Set<String>,
) : ClassLoader(parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        // Already defined by THIS loader? Return it (also covers a re-entrant
        // superclass resolution while defining a subclass).
        findLoadedClass(name)?.let { return it }
        if (name !in owned) return super.loadClass(name, resolve)
        val bytes =
            parent.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() }
                ?: throw ClassNotFoundException(name)
        val cls = defineClass(name, bytes, 0, bytes.size)
        if (resolve) resolveClass(cls)
        return cls
    }
}
