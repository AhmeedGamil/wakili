plugins {
    id("wakili.android.library")
    id("wakili.android.compose")
    id("wakili.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ahmedgamil.wakili.feature.terminal"
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.datastore)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
