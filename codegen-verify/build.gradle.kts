import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Proves the generated client compiles and behaves, by generating it during this build."

/**
 * The generator runs as an ordinary program against a schema, exactly as the Gradle plugin will in M9.
 * Keeping it in its own source set stops the generator's own dependencies from leaking onto the
 * classpath the generated code is compiled against — which is what makes this a real test of what a
 * user's project gets.
 */
val generator: SourceSet by sourceSets.creating

val generatedSources: Provider<Directory> = layout.buildDirectory.dir("generated/volan")

val schemaFile: RegularFile = layout.projectDirectory.file("schema/blog.volan")

val generateClient by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates the Volan client for blog.volan."
    classpath = generator.runtimeClasspath
    mainClass.set("verify.GenerateClientKt")
    inputs.file(schemaFile).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedSources)
    args(schemaFile.asFile.absolutePath, generatedSources.get().asFile.absolutePath)
}

sourceSets.main {
    kotlin.srcDir(generatedSources)
}

dependencies {
    "generatorImplementation"(project(":volan-codegen"))

    implementation(project(":volan-runtime"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(project(":volan-dialect-postgres"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgres)
    testRuntimeOnly(libs.jdbc.postgres)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn(generateClient)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
