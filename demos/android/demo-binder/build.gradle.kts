plugins { id("com.android.library") }

android {
    namespace = "dev.techlab.android.binder"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 26 }
    buildFeatures { aidl = true; viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
