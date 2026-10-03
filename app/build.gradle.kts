plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    // Session 5 — release signing (fill in locally; never commit keystore passwords):
    // signingConfigs {
    //     create("release") {
    //         storeFile = file(System.getenv("CHAMET_KEYSTORE") ?: "keystore.jks")
    //         storePassword = System.getenv("CHAMET_KEYSTORE_PASSWORD")
    //         keyAlias = System.getenv("CHAMET_KEY_ALIAS")
    //         keyPassword = System.getenv("CHAMET_KEY_PASSWORD")
    //     }
    // }
    // buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }

    namespace = "com.chamet.guesser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chamet.guesser"
        minSdk = 29
        targetSdk = 34
        versionCode = 111
        versionName = "8.3.1"

        // Optional: add your own keystore signing here later
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ML Kit — on-device OCR (text recognition v2)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // Room for local SQLite logging
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    testImplementation("junit:junit:4.13.2")
}
