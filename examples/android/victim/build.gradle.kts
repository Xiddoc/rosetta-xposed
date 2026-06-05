/*
 * The toy "victim" app — a tiny self-contained Android app whose one
 * interesting class is spelled like obfuscator output (`com.example.victim.a.b`
 * with method `c`). The LSPosed module hooks it by its REAL name through the
 * Rosetta map; no third-party app or APK is involved.
 *
 * LOOP #1 keeps R8 OFF so the runtime names are exactly what the bundled map
 * declares (deterministic, zero chicken-and-egg). To dogfood REAL obfuscation
 * (LOOP #2), flip `isMinifyEnabled = true` and feed R8 a fixed input mapping
 * so the obfuscated names still match the committed map — see ../README.md.
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.victim"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.victim"
        minSdk = 24
        targetSdk = 34
        versionCode = 100 // MUST match the map filename / version_code (maps/100.json).
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // LOOP #1: no obfuscation, so `a.b#c` is the real runtime spelling.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
