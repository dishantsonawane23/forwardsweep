plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.forwardsweep.ml"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:classifier"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Bundled models: work offline, no Play Services download on first use.
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.0")
    implementation("com.google.mlkit:face-detection:16.1.6")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
}
