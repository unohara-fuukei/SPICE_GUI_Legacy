plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.spice.nativeapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spice.nativeapp"
        minSdk = 24
        targetSdk = 29
        versionCode = 10
        versionName = "0.1.0-md3"
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("SPICE_KS") ?: "/home/user/.android-build/spicegui-release.jks"
            storeFile = file(ks)
            storePassword = System.getenv("SPICE_KSPASS") ?: "spice2026"
            keyAlias = System.getenv("SPICE_ALIAS") ?: "spicegui"
            keyPassword = System.getenv("SPICE_KSPASS") ?: "spice2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
}
