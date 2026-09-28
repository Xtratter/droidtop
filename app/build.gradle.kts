plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.xtratter.droidtop"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "io.github.xtratter.droidtop"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.6.2"
    }
    buildTypes {
        release {
            // R8: сжатие и оптимизация кода — приложение меньше и быстрее, чем debug-сборка
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Shizuku: права ADB (shell) без root — https://github.com/RikkaApps/Shizuku-API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
