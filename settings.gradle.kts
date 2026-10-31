pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ForwardSweep"
include(":core:classifier")
include(":core:ml")
include(":core:data")
include(":app")
include(":tools:harness")
