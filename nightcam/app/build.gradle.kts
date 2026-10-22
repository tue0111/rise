plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tue.nightcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tue.nightcam"
        // Pixel 3 XL dừng ở Android 12 (API 31). Không được nâng minSdk quá 31.
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.10.0"
    }

    signingConfigs {
        // Key cố định trong repo để mọi bản build (máy local hay CI) cài đè được lên nhau.
        // Chỉ dùng cho sideload cá nhân, không phát hành lên Play Store.
        create("sideload") {
            storeFile = rootProject.file("keystore/nightcam-sideload.jks")
            storePassword = "nightcam"
            keyAlias = "nightcam"
            keyPassword = "nightcam"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.preference:preference:1.2.1")

    // CameraX: lõi ghi hình giống XSCamera (đã chạy được trên máy thật), xem docs/XSCAMERA_DECOMPILE.md.
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
