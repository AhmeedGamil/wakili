plugins {
    id("wakili.android.library")
    id("wakili.hilt")
}

android {
    namespace = "com.ahmedgamil.wakili.core.datastore"
}

dependencies {
    api(projects.core.model)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
