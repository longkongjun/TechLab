buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Match GeckoView's Kotlin standard library metadata.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
}
