plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jtliaw.screentranslator"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.jtliaw.screentranslator"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.8"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // 固定签名：以后每次用 GitHub Actions 打出来的 APK 都能直接覆盖安装，不会丢设置
    signingConfigs {
        create("shared") {
            storeFile = file("shared-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 离线 OCR（拉丁字母，英文）与离线翻译，免费、不需要 API 密钥
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
}
