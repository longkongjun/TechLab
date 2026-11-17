plugins {
    id("com.android.library")
}

android {
    namespace = "dev.techlab.android.gecko"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("org.mozilla.geckoview:geckoview:160.0.20261104181552")
}
