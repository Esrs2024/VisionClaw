plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.morethantiles.inspector"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.morethantiles.inspector.vision"
        minSdk = 31
        targetSdk = 34
        versionCode = 2
        versionName = "1.1-arranque"
    }
    signingConfigs {
        create("inspectorStable") {
            storeFile = file("inspector-test.keystore")
            storePassword = "sample"
            keyAlias = "sample"
            keyPassword = "sample"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("inspectorStable") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("inspectorStable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.meta.wearable:mwdat-core:0.4.0")
    implementation("com.meta.wearable:mwdat-camera:0.4.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
