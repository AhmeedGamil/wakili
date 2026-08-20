import java.util.Properties

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
        versionCode = 4
        versionName = "0.4.0"
    }

    // Real release signing when <repo>/keystore.properties exists (see
    // keystore.properties.example); otherwise fall back to the debug key so a
    // fresh checkout still builds a sideloadable APK. The properties file and
    // the keystore itself are gitignored — they never leave this machine.
    val keystoreProperties = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreProperties.exists()) {
        val props = Properties().apply { keystoreProperties.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = rootProject.file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
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
    // Writes the baseline profiles bundled in the Compose AARs into ART at
    // install/first-run so cold starts are AOT-compiled instead of interpreted —
    // sideloaded installs never get Play cloud profiles.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
}
