plugins {
    id("volan.kotlin-library")
}

description = "Volan runtime: query planning, JDBC execution, result mapping, transactions and pooling."

dependencies {
    api(project(":volan-dialect-api"))
    api(libs.jspecify)
    implementation(libs.hikari)
    implementation(libs.slf4j.api)

    testImplementation(libs.jdbc.h2)
    testRuntimeOnly(libs.slf4j.simple)
}
