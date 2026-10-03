plugins {
    id("volan.published-library")
}

description = "Optional Micrometer instrumentation for Volan JDBC statements."

dependencies {
    api(project(":volan-runtime"))
    api(libs.micrometer.core)
    testImplementation(libs.jdbc.h2)
}
