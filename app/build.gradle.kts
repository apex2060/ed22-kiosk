import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from keystore.properties (local) or KIOSK_* env vars (CI).
// Every build must use the same key, or `adb install -r` updates will be rejected.
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)

android {
    namespace = "org.ed22.kiosk"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.ed22.kiosk"
        minSdk = 26
        targetSdk = 34
        // CI sets these from the run number so every push to main is a newer version (see README › Updates).
        versionCode = System.getenv("KIOSK_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("KIOSK_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        val storeFile = signingValue("storeFile", "KIOSK_KEYSTORE_FILE")
        if (storeFile != null) {
            create("release") {
                this.storeFile = rootProject.file(storeFile)
                storePassword = signingValue("storePassword", "KIOSK_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KIOSK_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KIOSK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
