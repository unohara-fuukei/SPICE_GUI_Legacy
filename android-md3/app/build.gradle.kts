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
        versionCode = 51
        versionName = "1.7.17"
        // 发布包只带 arm 两族；本地模拟器（x86_64）验证时用 -PtestAbis=x86_64 临时追加
        val testAbis = (project.findProperty("testAbis") as String?)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") + testAbis }
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
    testOptions { unitTests { isIncludeAndroidResources = true } }
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Robolectric + Compose UI 测试：无 KVM 无法跑模拟器，用 JVM 渲染复现闪退
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}
