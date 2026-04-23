plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.kuch.termx.feature.terminal"
    compileSdk = 35

    defaultConfig {
        minSdk = 28

        // Mirrored from :app so TerminalViewModel can read its own
        // BuildConfig without reaching into app's classpath.
        buildConfigField(
            "String",
            "TEST_SERVER_HOST",
            "\"${System.getenv("TERMX_TEST_SERVER_HOST") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "TEST_SERVER_USER",
            "\"${System.getenv("TERMX_TEST_SERVER_USER") ?: ""}\"",
        )
        buildConfigField(
            "int",
            "TEST_SERVER_PORT",
            "${System.getenv("TERMX_TEST_SERVER_PORT")?.toIntOrNull() ?: 22}",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":libs:terminal-view"))
    implementation(project(":libs:ssh-native"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
