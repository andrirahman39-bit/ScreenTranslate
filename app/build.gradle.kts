plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.screentranslate"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.screentranslate"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
