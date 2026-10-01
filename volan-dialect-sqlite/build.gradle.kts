plugins {
    id("volan.published-library")
}

description = "SQLite runtime dialect for Volan."

description = "SQLite dialect for Volan."

dependencies {
    api(project(":volan-dialect-api"))
    compileOnly(libs.jdbc.sqlite)

    testImplementation(libs.jdbc.sqlite)
}
