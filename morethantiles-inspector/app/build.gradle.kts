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
        versionCode = 3
        versionName = "2.0-meta-dat"
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"
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
    implementation("com.meta.wearable:mwdat-core:1.0.1")
    implementation("com.meta.wearable:mwdat-camera:1.0.1")
    implementation("com.meta.wearable:mwdat-speech:1.0.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
