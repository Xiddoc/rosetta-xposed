# R8 rules for the example LSPosed module.
#
# Why minify at all: un-minified, this module bundles the whole rosetta stack +
# kotlin + kotlinx.serialization and balloons to a multi-dex, multi-megabyte
# APK. LSPatch's embedded-module loader chokes on that shape (the patched app
# crashes in LSPApplication bootstrap with a swallowed NoClassDefFoundError),
# whereas a normal single-dex module loads fine. R8 collapses it to the shape a
# real LSPosed module ships in.

# The framework loads the module by the class names in assets/xposed_init and
# the manifest, so those entry points (and anything they expose to the
# framework) must survive shrinking/renaming.
-keep class com.example.rosettamodule.LegacyEntry { *; }
-keep class com.example.rosettamodule.ModernEntry { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }

# The Xposed API is provided by the framework at runtime (compileOnly), and the
# modern libxposed API is not on the classpath in this LOOP #1 build.
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**

# kotlinx.serialization ships its own consumer rules (applied automatically),
# but keep the rosetta map model's generated serializers explicitly so the
# attach-time map deserialization can never be shrunk out from under reflection.
-keepclassmembers class io.github.xiddoc.rosetta.**$$serializer { *; }
-keep,includedescriptorclasses class io.github.xiddoc.rosetta.core.model.** { *; }
