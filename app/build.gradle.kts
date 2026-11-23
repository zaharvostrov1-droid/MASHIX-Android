plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// libv2ray.aar (движок Xray-core для VLESS, ~58 МБ) больше лимита веб-загрузки
// GitHub (25 МБ), поэтому Gradle сам скачивает его с официального релиза
// 2dust/AndroidLibXrayLite при каждой сборке в Actions.
val libv2rayUrl = "https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.11.9/libv2ray.aar"
val libDir = File(projectDir, "libs")
val libv2rayAar = File(libDir, "libv2ray.aar")
if (!libv2rayAar.exists()) {
    libDir.mkdirs()
    ant.invokeMethod("get", mapOf("src" to libv2rayUrl, "dest" to libv2rayAar))
}

android {
    namespace = "app.mashix.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mashix.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "4.1.0"
        // В APK кладём только две архитектуры — остальное лишний вес
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // gomobile-библиотеки (libgojni.so) требуют распаковки на устройстве
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation(files(libv2rayAar))
}
