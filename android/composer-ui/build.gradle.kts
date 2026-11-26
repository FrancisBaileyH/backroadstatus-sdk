plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.backroadstatus.sdk.composer"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
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
    api(project(":core"))
    api(project(":mapkit"))

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.androidx.activity.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.androidsvg)

    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)
}
