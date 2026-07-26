@file:Suppress("UnstableApiUsage")

rootProject.name = "volan"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(
    ":volan-bom",
    ":volan-schema",
    ":volan-ir",
    ":volan-codegen",
    ":volan-dialect-api",
    ":volan-dialect-postgres",
    ":volan-dialect-mysql",
    ":volan-dialect-sqlite",
    ":volan-dialect-h2",
    ":volan-runtime",
    ":volan-migrate",
    ":volan-coroutines",
)
