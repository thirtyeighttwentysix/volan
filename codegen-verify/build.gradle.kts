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

val sqliteSources = layout.buildDirectory.dir("generated/sqlite")
val generateSqliteClient = tasks.register<JavaExec>("generateSqliteClient") {
    group = "build"
    description = "Generates a client from a SQLite-targeted schema."
    classpath = generator.runtimeClasspath
    mainClass.set("verify.GenerateClientKt")
    val sqliteSchema = layout.projectDirectory.file("schema/sqlite.volan")
    inputs.file(sqliteSchema).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(sqliteSources)
    args(sqliteSchema.asFile.absolutePath, sqliteSources.get().asFile.absolutePath)
}

val h2Sources = layout.buildDirectory.dir("generated/h2")
val generateH2Client = tasks.register<JavaExec>("generateH2Client") {
    group = "build"
    description = "Generates a client from an H2-targeted schema."
    classpath = generator.runtimeClasspath
    mainClass.set("verify.GenerateClientKt")
    val h2Schema = layout.projectDirectory.file("schema/h2.volan")
    inputs.file(h2Schema).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(h2Sources)
    args(h2Schema.asFile.absolutePath, h2Sources.get().asFile.absolutePath)
}

val mysqlSources = layout.buildDirectory.dir("generated/mysql")
val generateMySqlClient = tasks.register<JavaExec>("generateMySqlClient") {
    group = "build"
    description = "Generates a client covering MySQL Decimal and explicit UUID keys."
    classpath = generator.runtimeClasspath
    mainClass.set("verify.GenerateClientKt")
    val mysqlSchema = layout.projectDirectory.file("schema/mysql.volan")
    inputs.file(mysqlSchema).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(mysqlSources)
    args(mysqlSchema.asFile.absolutePath, mysqlSources.get().asFile.absolutePath)
}

sourceSets.main {
    kotlin.srcDir(generatedSources)
    kotlin.srcDir(sqliteSources)
    kotlin.srcDir(h2Sources)
    kotlin.srcDir(mysqlSources)
}

dependencies {
    "generatorImplementation"(project(":volan-codegen"))

    implementation(project(":volan-runtime"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(project(":volan-dialect-postgres"))
    testImplementation(project(":volan-dialect-sqlite"))
    testImplementation(project(":volan-dialect-h2"))
    testImplementation(project(":volan-dialect-mysql"))
    testImplementation(project(":volan-migrate"))
    testImplementation(libs.jdbc.sqlite)
    testImplementation(libs.jdbc.h2)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mariadb)
    testRuntimeOnly(libs.jdbc.mysql)
    testRuntimeOnly(libs.jdbc.mariadb)
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
        freeCompilerArgs.add("-Xemit-jvm-type-annotations")
    }
}

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn(generateClient, generateSqliteClient, generateH2Client, generateMySqlClient)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // H2 tests also use a different session zone, independently of the developer's machine.
    systemProperty("user.timezone", "UTC")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
