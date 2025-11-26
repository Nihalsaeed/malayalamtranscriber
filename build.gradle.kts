// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // FIX: Upgrade Android Plugin to 8.5.0 to support Android SDK 35 (Fixes XML error)
    id("com.android.application") version "8.5.0" apply false
    id("com.android.library") version "8.5.0" apply false

    // Kotlin 2.1.0 is required for LiteRT library
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}