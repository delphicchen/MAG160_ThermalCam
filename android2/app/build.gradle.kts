plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val thermalAssetsDir = layout.buildDirectory.dir("generated/thermalAssets")
val copyThermalAssets = tasks.register<Copy>("copyThermalAssets") {
    val assetsSrc = rootDir.resolve("recon")
    from(assetsSrc) { include("factory_nuc_grid.npz") }
    from(assetsSrc) { include("planck_luts.npy") }
    into(thermalAssetsDir)
}

android {
    namespace = "com.magnity.viewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.magnity.viewer"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "2.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources {
        noCompress += listOf("npz", "npy")
    }
    sourceSets {
        getByName("main") {
            assets.srcDir(thermalAssetsDir)
        }
    }
}

tasks.named("preBuild") { dependsOn(copyThermalAssets) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
}
