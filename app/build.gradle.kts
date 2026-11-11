plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    dependencies { }
}

android {
    namespace = "com.duykhai.gaming"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.duykhai.gaming"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
