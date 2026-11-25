plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    namespace = "com.navigationgps.desert"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.navigationgps.desert"
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "2.4.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 1-hour trial for fast-forward testing
            buildConfigField("long", "TRIAL_DURATION_MS", "1800000L")
        }
        release {
            // 3 days = 259,200,000 ms
            buildConfigField("long", "TRIAL_DURATION_MS", "259200000L")

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("main") {
            assets {
                srcDirs("src/main/assets")
            }
        }
    }
    androidResources {
        // Store PMTiles uncompressed in the APK: keeps AssetFileDescriptor length checks
        // reliable for bundled-tile integrity verification and avoids double decompression.
    }
    buildFeatures {
        viewBinding=true
        buildConfig = true
        compose = true
    }
}

// Kotlin 2.2+: kotlinOptions{} صار خطأ بناء — البديل compilerOptions
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    val room_version = "2.8.0"
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.core:core-splashscreen:1.0.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")
    implementation("androidx.sqlite:sqlite-framework:2.4.0")
    implementation("androidx.sqlite:sqlite:2.4.0")

    implementation ("com.google.android.gms:play-services-location:21.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.activity:activity-ktx:1.7.2")
    implementation("com.github.javiersantos:PiracyChecker:1.2.8")

    // osmdroid — Canvas-based map (legacy/broken GPU fallback)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Mapsforge — offline vector map rendering via osmdroid
    implementation("org.mapsforge:mapsforge-map-android:0.20.0")
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.20")

    // BRouter offline routing engine (pure JVM bytecode, zero native .so)
    implementation("com.github.abrensch:brouter:v1.7.9")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Google Play Billing Library
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    // Google Play In-App Update
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    // EncryptedSharedPreferences for secure trial storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Coroutines Play Services integration
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")
    // Chrome Custom Tabs
    implementation("androidx.browser:browser:1.8.0")
    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")
    // App Set ID API
    implementation("com.google.android.gms:play-services-appset:16.1.0")
}
