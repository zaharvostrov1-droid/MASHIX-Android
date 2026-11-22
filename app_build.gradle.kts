plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.mashix.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mashix.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "3.4.0"
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
}
