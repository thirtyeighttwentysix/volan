pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "volan-release-smoke"

dependencyResolutionManagement {
    repositories {
        val releaseRepository = providers.gradleProperty("volanRepository").orNull
        if (releaseRepository != null) {
            exclusiveContent {
                forRepository { maven { url = uri(releaseRepository) } }
                filter { includeGroup("io.github.thirtyeighttwentysix") }
            }
        }
        mavenCentral()
    }
}
