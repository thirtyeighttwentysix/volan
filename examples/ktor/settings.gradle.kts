pluginManagement {
    repositories {
        providers.gradleProperty("volanRepository").orNull?.let { maven { url = uri(it) } }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.github.thirtyeighttwentysix.volan") version providers.gradleProperty("volanVersion").getOrElse("1.0.0")
    }
}
dependencyResolutionManagement {
    repositories {
        providers.gradleProperty("volanRepository").orNull?.let { maven { url = uri(it) } }
        mavenCentral()
    }
}
rootProject.name = "volan-ktor"
