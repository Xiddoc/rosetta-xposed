# Keep Main as an unobfuscated entry point.
-keep class com.rosetta.dexfixture.Main { public static void main(java.lang.String[]); }

# Keep all classes in the package but allow obfuscation/renaming.
-keep,allowobfuscation class com.rosetta.dexfixture.** { *; }

# Don't optimize — prevents inlining of string literals/anchors.
-dontoptimize

# Apply deterministic name seed so obf names are reproducible.
-applymapping seed.txt

# Note: -printmapping is passed dynamically by build.sh so the path can be
# set to the correct output directory. This file must not contain a
# -printmapping directive; build.sh appends one via a temp conf fragment.
