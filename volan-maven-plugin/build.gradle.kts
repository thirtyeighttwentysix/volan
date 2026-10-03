plugins {
    id("volan.published-library")
}

description = "Automatic Volan client generation during the Maven generate-sources phase."

dependencies {
    implementation(project(":volan-codegen"))
    compileOnly(libs.maven.plugin.api)
    compileOnly(libs.maven.core)
    testImplementation(libs.maven.plugin.api)
    testImplementation(libs.maven.core)
}

mavenPublishing { pom { packaging = "maven-plugin" } }

tasks.processResources {
    val volanVersion = project.version.toString()
    inputs.property("volanVersion", volanVersion)
    filesMatching("META-INF/maven/plugin.xml") { filter { it.replace("@VOLAN_VERSION@", volanVersion) } }
}
