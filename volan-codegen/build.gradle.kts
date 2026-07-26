plugins {
    id("volan.kotlin-library")
}

description = "KotlinPoet-based generator producing the type-safe Volan client."

dependencies {
    api(project(":volan-ir"))
    implementation(libs.kotlinpoet)
}

// `./gradlew :volan-codegen:test -Pvolan.updateGolden=true` rewrites the golden files from what the
// generator produces, so a deliberate change to generated code is one command and one diff to review.
tasks.test {
    systemProperty("volan.updateGolden", providers.gradleProperty("volan.updateGolden").getOrElse("false"))
}
