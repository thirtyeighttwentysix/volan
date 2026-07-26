plugins {
    id("volan.kotlin-library")
}

description = "Database introspection, schema diffing and SQL migration generation."

dependencies {
    api(project(":volan-ir"))
    api(project(":volan-dialect-api"))
    implementation(project(":volan-runtime"))
    implementation(libs.slf4j.api)

    testImplementation(libs.jdbc.h2)
    testImplementation(libs.kotest.property)
}
