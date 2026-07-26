plugins {
    id("volan.kotlin-library")
}

description = "KotlinPoet-based generator producing the type-safe Volan client."

dependencies {
    api(project(":volan-ir"))
    implementation(libs.kotlinpoet)
}
