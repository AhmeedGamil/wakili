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
}
