plugins {
    id("com.android.library")
}

android {
    buildFeatures { viewBinding = true }
    namespace = "dev.techlab.android.gecko"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("org.mozilla.geckoview:geckoview:160.0.20261104181552")
}
