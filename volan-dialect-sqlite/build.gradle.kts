plugins {
    id("volan.kotlin-library")
}

description = "SQLite dialect for Volan."

dependencies {
    api(project(":volan-dialect-api"))
    compileOnly(libs.jdbc.sqlite)

    testImplementation(libs.jdbc.sqlite)
}
