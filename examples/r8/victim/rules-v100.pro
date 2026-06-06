# Keep the victim's members but allow renaming, so R8 actually obfuscates.
-keep,allowobfuscation class com.example.victim.** { *; }

# Don't optimize/inline — keeps the example's mapping 1:1 and readable.
-dontoptimize

# Apply the deterministic name seed for v100 (resolved relative to this dir).
-applymapping seed-v100.txt
