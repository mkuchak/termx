import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val appVersionName: String = run {
    val pkg = JsonSlurper().parse(rootProject.file("../package.json")) as Map<*, *>
    pkg["version"] as String
}

val appVersionCode: Int = run {
    // Monotonic versionCode derived from total git commit count on HEAD.
    // Falls back to 1 if git is unavailable (e.g., shallow CI checkout without full history).
    try {
        val out = ByteArrayOutputStream()
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            workingDir = rootProject.projectDir
            standardOutput = out
            isIgnoreExitValue = true
        }.result.get()
        val txt = out.toString().trim()
        if (txt.isEmpty()) 1 else txt.toInt()
    } catch (_: Exception) {
        1
    }
}

android {
    namespace = "dev.kuch.termx"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.kuch.termx"
        minSdk = 28
        targetSdk = 34
        versionName = appVersionName
        versionCode = appVersionCode

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val ksB64 = System.getenv("ANDROID_KEYSTORE_BASE64")
            if (!ksB64.isNullOrBlank()) {
                val ksFile = File.createTempFile("termx-ks", ".jks").apply {
                    writeBytes(java.util.Base64.getDecoder().decode(ksB64))
                    deleteOnExit()
                }
                storeFile = ksFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (System.getenv("ANDROID_KEYSTORE_BASE64").isNullOrBlank()) {
                null
            } else {
                signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "termx-v${defaultConfig.versionName}-${buildType.name}.apk"
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":feature:servers"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:keys"))
    implementation(project(":feature:ptt"))
    implementation(project(":feature:settings"))
    implementation(project(":libs:terminal-view"))
    implementation(project(":libs:ssh-native"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

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
