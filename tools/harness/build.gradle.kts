plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:classifier"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.drewnoakes:metadata-extractor:2.19.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.17.1")
}

application {
    mainClass.set("com.forwardsweep.harness.MainKt")
    applicationName = "forwardsweep"
}
