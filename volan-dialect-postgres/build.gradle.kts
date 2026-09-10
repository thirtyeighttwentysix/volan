plugins {
    id("volan.published-library")
}

description = "PostgreSQL dialect for Volan."

dependencies {
    api(project(":volan-dialect-api"))
    compileOnly(libs.jdbc.postgres)

    testImplementation(libs.jdbc.postgres)
}
