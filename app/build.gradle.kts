plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.claudewake"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.claudewake"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "1.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
