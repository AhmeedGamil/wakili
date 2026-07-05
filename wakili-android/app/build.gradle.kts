plugins {
    id("wakili.android.application")
    id("wakili.android.compose")
    id("wakili.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ahmedgamil.wakili"

    defaultConfig {
        applicationId = "com.ahmedgamil.wakili"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sideload-friendly until a real release keystore is set up.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.feature.connect)
    implementation(projects.feature.sessions)
    implementation(projects.feature.chat)
    implementation(projects.feature.files)
    implementation(projects.feature.terminal)
    implementation(projects.feature.settings)
    implementation(projects.core.datastore)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
}
