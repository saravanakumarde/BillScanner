plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// The GitHub Actions build workflow builds a fresh debug APK on every run.
// Gradle's auto-generated debug keystore is per-machine, so on a CI runner
// (a brand new VM every time) each build used to get a DIFFERENT signing
// key — Android then refuses to install the new APK over the old one
// ("app not installed", or it silently requires an uninstall first) because
// their signatures don't match. Signing every build with this checked-in,
// stable keystore instead keeps the signature identical across builds, so
// updating from a new APK works like a normal app update, keeping data.
//
// A version code that never increases has the same "can't update" effect
// (Android also refuses same-or-lower versionCode over an installed app).
// GITHUB_RUN_NUMBER increases by one on every workflow run, so it's used
// here as the versionCode; it defaults to 1 for local builds outside CI.
val autoVersionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.billscanner.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.billscanner.app"
        minSdk = 26
        targetSdk = 34
        versionCode = autoVersionCode
        versionName = "1.$autoVersionCode"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("stable") {
            storeFile = file("keystore/billscanner-release.keystore")
            storePassword = "billscanner123"
            keyAlias = "billscanner"
            keyPassword = "billscanner123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("stable")
        }
        debug {
            isMinifyEnabled = false
            // Use the same stable signing key as release so a debug APK
            // built today can be installed over one built weeks ago
            // (or on a different CI runner) without uninstalling first.
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Core AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Room (database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // CameraX is not used — the system camera app is launched via Intent
    // instead, so the user can pick Xiaomi's "Documents" scan mode.

    // ML Kit on-device text recognition (Latin script incl. German)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Charts for category/weekly/monthly summaries
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Image loading (thumbnails from MediaStore URIs)
    implementation("io.coil-kt:coil:2.6.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
