plugins {
    id("wakili.android.library")
    id("wakili.hilt")
}

android {
    namespace = "com.ahmedgamil.wakili.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.network)
    implementation(projects.core.datastore)
    implementation(projects.core.common)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.retrofit)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
