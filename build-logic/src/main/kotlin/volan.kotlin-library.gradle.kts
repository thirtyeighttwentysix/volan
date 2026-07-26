import dev.detekt.gradle.Detekt
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
    id("org.jetbrains.kotlinx.kover")
}

private val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    explicitApi()

    // Calling this enables Kotlin's built-in ABI validation for the module.
    abiValidation()

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
        allWarningsAsErrors.set(true)
    }
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
    }
}

ktlint {
    version.set(libs.findVersion("ktlint").get().requiredVersion)
    filter {
        exclude { it.file.path.contains("${File.separator}build${File.separator}") }
    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(isolated.rootProject.projectDirectory.file("config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("kotest-assertions").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

/**
 * Stable aliases for the Kotlin built-in ABI validation tasks, so that the documented
 * `./gradlew apiCheck` / `./gradlew apiDump` commands keep working regardless of the
 * underlying implementation.
 */
tasks.register("apiCheck") {
    group = "verification"
    description = "Verifies that the public ABI matches the checked-in dump."
    dependsOn(tasks.named("checkKotlinAbi"))
}

tasks.register("apiDump") {
    group = "verification"
    description = "Regenerates the checked-in public ABI dump."
    dependsOn(tasks.named("updateKotlinAbi"))
}
