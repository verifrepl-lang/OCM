plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionName = (findProperty("appVersionName") as String?) ?: "1.1.0"
val appVersionCode = ((findProperty("appVersionCode") as String?) ?: "1").toInt()
val ksPass = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "opencodemob"

android {
    namespace = "app.opencodemob"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.opencodemob"
        minSdk = 30
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GITHUB_REPO", "\"verifrepl-lang/OCM\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = ksPass
            keyAlias = "opencodemob"
            keyPassword = ksPass
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
}
