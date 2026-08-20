plugins {
    id("wakili.android.library")
    id("wakili.android.compose")
}

android {
    namespace = "com.ahmedgamil.wakili.core.ui"
}

dependencies {
    api(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.telephoto.zoomable.image.coil3)
}
