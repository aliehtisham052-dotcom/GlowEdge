plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.innovation313.glowedge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.innovation313.glowedge"
        minSdk = 26
        targetSdk = 36
        versionCode = 40
        versionName = "6.4"
    }

    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("glowedge.keystore")
            storePassword = "glowedge313"
            keyAlias = "glowedge"
            keyPassword = "glowedge313"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
