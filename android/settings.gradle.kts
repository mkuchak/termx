pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "termx"

// v1.1.21: ssp-transport (pure-Kotlin mosh client) wired as a Gradle
// composite build. The vendored sources live under libs/mosh-transport/
// and ship their own build.gradle.kts (Kotlin/JVM, protobuf-javalite).
// The substitution lets `:libs:ssh-native` declare a normal coordinate
// dependency that resolves to the included build.
includeBuild("libs/mosh-transport") {
    dependencySubstitution {
        substitute(module("sh.haven:ssp-transport")).using(project(":"))
    }
}

include(
    ":app",
    ":core:common",
    ":core:domain",
    ":core:data",
    ":feature:servers",
    ":feature:terminal",
    ":feature:keys",
    ":feature:onboarding",
    ":feature:ptt",
    ":feature:settings",
    ":feature:updater",
    ":libs:companion",
    ":libs:ssh-native",
    ":libs:terminal-view",
)
