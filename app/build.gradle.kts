import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Identity of the guest disk this build ships — the SHA-256 of the asset itself.
 *
 * The app extracts the disk into its own storage on first launch and only re-extracts when the marker
 * it wrote is gone, so that marker MUST change whenever the image changes. A hand-maintained counter
 * did not: two releases shipped different images under the same marker, so installs of the earlier
 * one kept the old guest and every fix in the newer image was invisible on the device. Deriving the
 * marker from the file makes that impossible.
 */
val guestImageSha256: String = run {
    val image = file("src/main/assets/vm/base.qcow2.gz")
    if (!image.exists()) {
        logger.lifecycle("WARNING: ${image.path} is missing — run image/build_guest_image.sh")
        "missing"
    } else {
        val digest = MessageDigest.getInstance("SHA-256")
        image.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

android {
    namespace = "com.romirmile.hermes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.romirmile.hermeslinux"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "1.2.1"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "GUEST_IMAGE_SHA256", "\"$guestImageSha256\"")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

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
