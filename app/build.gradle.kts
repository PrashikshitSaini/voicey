plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.prashikshit.voicey"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.prashikshit.voicey"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.1.9"
        vectorDrawables.useSupportLibrary = true
    }

    // Public, stable signing identity for sideloaded builds. Using the same certificate
    // in debug artifacts and releases lets Android install every future APK as an
    // in-place update while preserving app data and granted permissions.
    signingConfigs {
        create("voicey") {
            storeFile = file("voicey-release.keystore")
            storePassword = "voicey-public-signing"
            keyAlias = "voicey"
            keyPassword = "voicey-public-signing"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("voicey")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("voicey")
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
