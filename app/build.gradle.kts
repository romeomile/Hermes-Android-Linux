plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.romirmile.hermes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.romirmile.hermeslinux"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // The guest disk image and the kernel/initrd are already compressed; aapt2 must not unpack them.
    androidResources {
        noCompress += listOf("gz", "virt")
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // QEMU and qemu-img ship as jniLibs; Android only lets the app execute them when they are
        // extracted to disk, which legacy packaging guarantees.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // The interface is a WebView running the vendored shell; WebViewAssetLoader serves those assets
    // from an https origin instead of file://, which the shell needs for DOM storage.
    implementation("androidx.webkit:webkit:1.12.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
