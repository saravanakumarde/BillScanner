pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Plugin versions are declared here (single source of truth) so the
    // build resolves them even if the root build.gradle.kts is missing or
    // not picked up, which is what caused "Plugin [id: 'com.android.application']
    // was not found" on CI.
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "1.9.24"
        id("com.google.devtools.ksp") version "1.9.24-1.0.20"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "BillScanner"
include(":app")
