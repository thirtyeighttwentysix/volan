plugins {
    id("volan.kotlin-library")
    application
}

description = "The Volan PostgreSQL and SQLite migration command line."

dependencies {
    implementation(project(":volan-migrate"))
    implementation(project(":volan-dialect-postgres"))
    implementation(project(":volan-dialect-sqlite"))
    implementation(libs.clikt)
    runtimeOnly(libs.jdbc.postgres)
    runtimeOnly(libs.jdbc.sqlite)
}

application {
    mainClass.set("io.github.thirtyeighttwentysix.volan.cli.MainKt")
    applicationName = "volan"
}
