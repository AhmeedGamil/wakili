plugins {
    id("wakili.android.library")
    id("wakili.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ahmedgamil.wakili.core.network"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
}
