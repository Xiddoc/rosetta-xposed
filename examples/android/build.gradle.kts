/*
 * Root build script for the Android dogfood. Plugins are declared (versions in
 * settings.gradle.kts pluginManagement) and applied per-module.
 */
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.android") apply false
}
