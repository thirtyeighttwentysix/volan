import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("volan.kotlin-library")
    `java-gradle-plugin`
    alias(libs.plugins.dokka)
    id("volan.publishing")
}

description = "Automatic Volan client generation for Kotlin JVM Gradle builds."
mavenPublishing {
    configure(GradlePlugin(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = SourcesJar.Sources()))
}

dependencies {
    implementation(project(":volan-codegen"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("volan") {
            id = "io.github.thirtyeighttwentysix.volan"
            implementationClass = "io.github.thirtyeighttwentysix.volan.gradle.VolanPlugin"
            displayName = "Volan client generation"
            description = project.description
        }
    }
}

tasks.processResources {
    val volanVersion = project.version.toString()
    inputs.property("volanVersion", volanVersion)
    filesMatching("volan-plugin.properties") { expand("volanVersion" to volanVersion) }
}
