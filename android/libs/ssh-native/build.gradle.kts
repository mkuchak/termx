plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.kuch.termx.libs.sshnative"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // CapturingMoshLogger calls android.util.Log.{d,w} from
        // production code; without this the JVM unit tests throw
        // "Method d/w not mocked" on first invocation.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.sshj)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.coroutines.android)

    // v1.1.21: pure-Kotlin mosh client transport. Resolved via the
    // composite-build substitution declared in settings.gradle.kts;
    // the actual sources live at libs/mosh-transport/ in this repo.
    implementation("sh.haven:ssp-transport:0.1.0")

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.sshd.core)
    testImplementation(libs.bouncycastle.bcprov)
}
