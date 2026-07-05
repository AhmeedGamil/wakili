plugins {
    id("wakili.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // JsonObject is the lingua franca for tool inputs (arbitrary JSON from agents).
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
