plugins {
    id("volan.kotlin-library")
    application
}

description = "The Volan PostgreSQL migration command line."

dependencies {
    implementation(project(":volan-migrate"))
    implementation(project(":volan-dialect-postgres"))
    implementation(libs.clikt)
    runtimeOnly(libs.jdbc.postgres)
}

application {
    mainClass.set("io.github.thirtyeighttwentysix.volan.cli.MainKt")
    applicationName = "volan"
}
