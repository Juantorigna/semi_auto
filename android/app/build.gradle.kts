plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.areacamperbergamo.kiosk"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.areacamperbergamo.kiosk"
        minSdk = 26  // Stripe Terminal SDK requires API 26+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Base URL for the web app — change to your production domain
        buildConfigField("String", "KIOSK_BASE_URL", "\"https://areacamperbergamo.it/main/html/checkout.html\"")

        // Base URL for the API (connection-token, create-payment-intent)
        buildConfigField("String", "API_BASE_URL", "\"https://areacamperbergamo.it/app/api\"")

        // Set to true to use the simulated card reader (no physical hardware needed)
        buildConfigField("boolean", "USE_SIMULATED_READER", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // In production, use the real reader
            buildConfigField("boolean", "USE_SIMULATED_READER", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.10.0")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Stripe Terminal SDK for in-person payments (BBPOS WisePad 3)
    implementation("com.stripe:stripeterminal:3.5.1")

    // OkHttp for server communication (connection-token, create-payment-intent)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.json:json:20231013")
}
