plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.backroadstatus.sdk.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.appauth)

    // Compose Color on `Condition` mirrors SwiftUI.Color on iOS Core.
    api(platform(libs.compose.bom))
    api(libs.compose.ui.graphics)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
}
