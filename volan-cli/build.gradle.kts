plugins {
    id("volan.kotlin-library")
    application
}

description = "The Volan database schema command line."

dependencies {
    implementation(project(":volan-migrate"))
    implementation(project(":volan-dialect-postgres"))
    implementation(project(":volan-dialect-sqlite"))
    implementation(project(":volan-dialect-h2"))
    implementation(libs.clikt)
    runtimeOnly(libs.jdbc.postgres)
    runtimeOnly(libs.jdbc.sqlite)
    runtimeOnly(libs.jdbc.h2)
}

application {
    mainClass.set("io.github.thirtyeighttwentysix.volan.cli.MainKt")
    applicationName = "volan"
}
