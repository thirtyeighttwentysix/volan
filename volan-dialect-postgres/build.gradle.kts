plugins {
    id("volan.kotlin-library")
}

description = "PostgreSQL dialect for Volan."

dependencies {
    api(project(":volan-dialect-api"))
    compileOnly(libs.jdbc.postgres)

    testImplementation(libs.jdbc.postgres)
}
