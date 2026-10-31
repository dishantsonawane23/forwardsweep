plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    // Same `ai.onnxruntime` API on JVM and Android: the harness adds `onnxruntime`,
    // the app adds `onnxruntime-android`.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.17.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

tasks.test { useJUnitPlatform() }
