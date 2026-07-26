plugins {
    id("volan.kotlin-library")
}

description = "Coroutine-aware wrappers over the synchronous Volan runtime."

dependencies {
    api(project(":volan-runtime"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.jdbc.h2)
}
