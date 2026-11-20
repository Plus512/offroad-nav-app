plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alruwaili.dalilalbar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alruwaili.dalilalbar"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // محرك عرض خرائط متجهة (vector tiles) مفتوح المصدر — يسمح بتلوين مخصص للطبقات (غطاء نباتي، مجارٍ مائية، إلخ)
    // بخلاف بلاطات الصور الجاهزة التي لا يمكن تغيير ألوانها. يستخدم بيانات OpenFreeMap المجانية بلا مفتاح API.
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    // مقياس الرسم أسفل يمين الشاشة
    implementation("org.maplibre.gl:android-plugin-scalebar-v9:3.0.2")
}
