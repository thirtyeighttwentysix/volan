plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.build.kotlin)
    implementation(libs.build.ktlint)
    implementation(libs.build.detekt)
    implementation(libs.build.kover)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation")
    }
}
