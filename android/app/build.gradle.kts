plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The neural-SR models, factory NUC grid and Planck LUTs live at the repository root
// (shared with the Linux app). Copy them into the APK assets at build time so the two
// ports can never drift apart.
val thermalAssetsDir = layout.buildDirectory.dir("generated/thermalAssets")
val copyThermalAssets = tasks.register<Copy>("copyThermalAssets") {
    val repoRoot = rootDir.parentFile
    from(repoRoot) {
        include(
            "thermal_espcn_2x.onnx", "thermal_espcn_2x.onnx.data",
            "thermal_espcn_4x.onnx", "thermal_espcn_4x.onnx.data",
            "factory_nuc_grid.npz",
        )
    }
    from(File(repoRoot, "recon")) { include("planck_luts.npy") }
    into(thermalAssetsDir)
}

android {
    namespace = "com.magnity.thermalcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.magnity.thermalcam"
        // Dimensity 9200 devices ship with Android 13 (API 33) or later.
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk {
            // Target class: Dimensity 9200+ — 64-bit only.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }
    androidResources {
        // keep the model / calibration blobs stored uncompressed so AssetFileDescriptor
        // access works and first-run copies stream fast
        noCompress += listOf("onnx", "data", "npz", "npy")
    }
    sourceSets {
        getByName("main").assets.srcDir(thermalAssetsDir)
    }
}

tasks.named("preBuild") { dependsOn(copyThermalAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Neural super-resolution: ONNX Runtime with the NNAPI EP (MediaTek APU on
    // Dimensity 9200) and XNNPACK/CPU fallback.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // RGB sensor fusion: phone camera luma stream via CameraX ImageAnalysis.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
}
