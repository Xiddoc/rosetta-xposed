/*
 * Deferred binding (RFC 0001 Decision 2, "Deferred binding").
 *
 * Name *resolution* is always available — it's data. But the *hook* must
 * wait until the declaring class is loadable. For packed/hardened apps that
 * load dex late, the real class loader isn't the one present at module init;
 * the target class appears only after the app's own loader runs.
 *
 * STATUS: architected, not yet built. When implemented, this captures the
 * real class loader — by hooking `DexClassLoader` / `ClassLoader.loadClass`,
 * or via libxposed's `onPackageReady().getClassLoader()` — and binds when
 * the target first appears. Map metadata (`dex`, `kind`, `extends`, anchors)
 * informs whether deferral is even needed. The common case (class already
 * loadable) is handled directly by [MethodTarget] / [FieldTarget] /
 * [ClassTarget] today.
 */
package io.github.xiddoc.rosetta.xposed

public object DeferredBinding {
    /**
     * Run [onAvailable] with a [RosettaXposed] bound to the real class
     * loader once [realClass]'s declaring class becomes loadable.
     *
     * Not yet implemented — see the file header. The synchronous binding
     * path on the target types covers apps that load their dex up front.
     */
    public fun whenClassAvailable(
        realClass: String,
        onAvailable: (RosettaXposed) -> Unit,
    ): Nothing =
        throw NotImplementedError(
            "rosetta-xposed: deferred binding for late-loaded dex is planned for a later phase " +
                "(RFC 0001 Decision 2). If '$realClass' is already loadable at hook time, bind it " +
                "directly via RosettaXposed.method(...)/field(...)/useClass(...).",
        )
}
