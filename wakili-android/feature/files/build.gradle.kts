plugins {
    id("wakili.android.library")
    id("wakili.android.compose")
    id("wakili.hilt")
}

android {
    namespace = "com.ahmedgamil.wakili.feature.files"
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
