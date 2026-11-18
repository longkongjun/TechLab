plugins {
    id("com.android.application")
}

val demoAbi = providers.gradleProperty("techlabAbi").orNull

android {
    buildFeatures { viewBinding = true }
    namespace = "dev.techlab.android"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.techlab.android"
        minSdk = 26
        targetSdk = 32
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "dev.techlab.android.ProducerProbe"
        ndk {
            abiFilters += if (demoAbi == null) setOf("arm64-v8a", "armeabi-v7a", "x86_64") else setOf(demoAbi)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":demo-geckoview"))
    implementation(project(":demo-handwriting"))
}
