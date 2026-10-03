pluginManagement {
    repositories {
        val staged = providers.gradleProperty("volanRepository").orNull
        if (staged != null) maven { url = uri(staged) }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.github.thirtyeighttwentysix.volan") version providers.gradleProperty("volanVersion").getOrElse("0.1.0-alpha.3")
    }
}

dependencyResolutionManagement {
    repositories {
        val staged = providers.gradleProperty("volanRepository").orNull
        if (staged != null) maven { url = uri(staged) }
        mavenCentral()
    }
}
rootProject.name = "volan-gradle-consumer"
