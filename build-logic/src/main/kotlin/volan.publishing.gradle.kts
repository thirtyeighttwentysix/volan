plugins {
    id("com.vanniktech.maven.publish.base")
}

mavenPublishing {
    if (!providers.gradleProperty("volanUnsignedLocalPublication").isPresent) {
        publishToMavenCentral()
        signAllPublications()
    }
    pom {
        name.set(project.name)
        description.set(provider { project.description })
        url.set("https://github.com/thirtyeighttwentysix/volan")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("thirtyeighttwentysix")
                name.set("thirtyeighttwentysix")
                url.set("https://github.com/thirtyeighttwentysix")
            }
        }
        scm {
            url.set("https://github.com/thirtyeighttwentysix/volan")
            connection.set("scm:git:https://github.com/thirtyeighttwentysix/volan.git")
            developerConnection.set("scm:git:ssh://git@github.com/thirtyeighttwentysix/volan.git")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "ReleaseTest"
            url = uri(isolated.rootProject.projectDirectory.dir("build/release-repository"))
        }
    }
}
