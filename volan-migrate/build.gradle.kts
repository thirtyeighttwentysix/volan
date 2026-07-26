plugins {
    id("volan.kotlin-library")
}

description = "Database introspection, schema diffing and SQL migration generation."

dependencies {
    api(project(":volan-ir"))
    api(project(":volan-dialect-api"))
    implementation(project(":volan-runtime"))
    implementation(libs.slf4j.api)

    testImplementation(project(":volan-dialect-postgres"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgres)
    testRuntimeOnly(libs.jdbc.postgres)
    testImplementation(libs.jdbc.h2)
    testImplementation(libs.kotest.property)
}

// `./gradlew :volan-migrate:test -Pvolan.updateGolden=true` rewrites the golden migration from what the
// differ produces, so a deliberate change to generated SQL is one command and one diff to review.
tasks.test {
    systemProperty("volan.updateGolden", providers.gradleProperty("volan.updateGolden").getOrElse("false"))
}
