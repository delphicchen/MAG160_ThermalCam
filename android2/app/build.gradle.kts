plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.magnity.viewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.magnity.viewer"
        minSdk = 33
        targetSdk = 35
        versionCode = 15
        versionName = "2.7-alpha6"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }
        }
    }

    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        // ncnn model weights must not be compressed — they are mmap'd from the APK
        noCompress += listOf("param", "bin")
    }

    defaultConfig.manifestPlaceholders["appLabel"] = "MagViewer"

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // Side-by-side test build: own application id, so it installs next to the stable
        // app (separate settings and model dir) and is labelled "MagViewer β".
        create("beta") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            manifestPlaceholders["appLabel"] = "MagViewer β"
            matchingFallbacks += listOf("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        // the AC020 SDK ships its own libc++_shared (older NDK); ours from CMake wins
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    // visible-camera fusion: phone camera luma stream via CameraX ImageAnalysis
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    // InfiRay Tiny2C (AC020 UVC) — vendor SDK 1.1.5 from Tiny-2C/SDK/Android/sdk/
    implementation(files("libs/ac020/libAC020sdk_USB_IR_UVC_1.1.5.aar",
                         "libs/ac020/libcommon_1.2.0_2409191614.aar",
                         "libs/ac020/libirutils_1.2.1.aar"))
}
