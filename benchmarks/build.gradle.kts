import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Reproducible PostgreSQL reads across Volan, Hibernate, Exposed, jOOQ and JDBC."

val generator: SourceSet by sourceSets.creating
val generatedSources = layout.buildDirectory.dir("generated/volan")
val schemaFile = layout.projectDirectory.file("schema/bench.volan")

val generateClient by tasks.registering(JavaExec::class) {
    classpath = generator.runtimeClasspath
    mainClass.set("bench.GenerateClientKt")
    inputs.file(schemaFile).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedSources)
    args(schemaFile.asFile.absolutePath, generatedSources.get().asFile.absolutePath)
}

sourceSets.main { kotlin.srcDir(generatedSources) }

dependencies {
    "generatorImplementation"(project(":volan-codegen"))
    implementation(project(":volan-runtime"))
    implementation(project(":volan-dialect-postgres"))
    implementation(libs.hikari)
    implementation(libs.jdbc.postgres)
    implementation(libs.hibernate.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.jooq)
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.processor)
    runtimeOnly(libs.slf4j.simple)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
tasks.named("compileKotlin") { dependsOn(generateClient) }

val benchmarkArgs = providers.gradleProperty("benchmarkArgs").orElse("")
val resultsFile = layout.buildDirectory.file("results/jmh.json")

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs JMH against a dedicated PostgreSQL database configured with VOLAN_BENCH_URL."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args("bench.OrmBenchmark", "-rf", "json", "-rff", resultsFile.get().asFile.absolutePath, "-foe", "true")
    args(benchmarkArgs.get().split(Regex("\\s+")).filter { it.isNotBlank() })
    doFirst { resultsFile.get().asFile.parentFile.mkdirs() }
}
