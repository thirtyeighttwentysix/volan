plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.build.kotlin)
    implementation(libs.build.ktlint)
    implementation(libs.build.detekt)
    implementation(libs.build.kover)
    implementation(libs.build.dokka)
    implementation(libs.build.maven.publish)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation")
    }
}
