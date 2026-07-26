plugins {
    id("volan.kotlin-library")
}

description = "H2 dialect for Volan."

dependencies {
    api(project(":volan-dialect-api"))
    compileOnly(libs.jdbc.h2)

    testImplementation(libs.jdbc.h2)
}
