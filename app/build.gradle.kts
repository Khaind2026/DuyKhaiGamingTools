plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

android {
    buildFeatures { aidl = true }
    namespace = "com.duykhai.gaming"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.duykhai.gaming"
        minSdk = 28
        targetSdk = 34
        versionCode = 7
        versionName = "0.6.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
