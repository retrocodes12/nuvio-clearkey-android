plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nuvio.ckplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nuvio.ckplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.9.0"
    }

    // Read signing config from Gradle -P properties (passed explicitly on the CI
    // command line) — reliably seen by findProperty regardless of daemon/env.
    val keystorePath = (project.findProperty("nebulaKeystore") as String?)?.takeIf { it.isNotBlank() }
    logger.lifecycle("Nebula release signing: keystore ${if (keystorePath != null) "PRESENT -> fixed release key" else "ABSENT -> debug fallback"}")

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = project.findProperty("nebulaStorePassword") as String?
                keyAlias = project.findProperty("nebulaKeyAlias") as String?
                keyPassword = project.findProperty("nebulaKeyPassword") as String?
            }
        }
    }

    buildTypes {
        debug {
            // debug-signed by default so local/dev builds need no keystore
        }
        release {
            isMinifyEnabled = false
            // One fixed release key in CI (from repo secrets) so every build shares
            // a signature and updates install over the top — no uninstall. Falls
            // back to debug signing for local builds without the keystore.
            signingConfig = if (keystorePath != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
}
