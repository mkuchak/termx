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

include(
    ":app",
    ":core:common",
    ":core:domain",
    ":core:data",
    ":feature:servers",
    ":feature:terminal",
    ":feature:keys",
    ":feature:ptt",
    ":feature:settings",
    ":libs:companion",
    ":libs:ssh-native",
    ":libs:terminal-view",
)
