plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phoenix.warpscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phoenix.warpscanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "1.7"
        ndk { abiFilters += "arm64-v8a" }
    }

    packaging {
        // Extract native libs at install so the bundled scanner engine
        // (jniLibs/arm64-v8a/libcf-scanner.so) can be executed from
        // applicationInfo.nativeLibraryDir.
        jniLibs { useLegacyPackaging = true }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "release.jks"
            storeFile = file(keystorePath)
            if (keystorePath.endsWith(".p12") || keystorePath.endsWith(".pfx")) {
                storeType = "PKCS12"
            }
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.json:json:20231013")
}
