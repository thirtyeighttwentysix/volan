import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
}

val volanVersion = providers.gradleProperty("volanVersion").get()
val includesSqlite = volanVersion != "0.1.0-alpha.1"
val includesH2 = volanVersion !in setOf("0.1.0-alpha.1", "0.1.0-alpha.2")
val generator = sourceSets.create("generator")
val generatedSources = layout.buildDirectory.dir("generated/volan")

dependencies {
    add(generator.implementationConfigurationName, "io.github.thirtyeighttwentysix:volan-codegen:$volanVersion")
    implementation(platform("io.github.thirtyeighttwentysix:volan-bom:$volanVersion"))
    implementation("io.github.thirtyeighttwentysix:volan-runtime")
    implementation("io.github.thirtyeighttwentysix:volan-dialect-postgres")
    testImplementation("io.github.thirtyeighttwentysix:volan-migrate")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testRuntimeOnly("org.postgresql:postgresql:42.7.13")
    if (includesSqlite) {
        testImplementation("io.github.thirtyeighttwentysix:volan-dialect-sqlite")
        testRuntimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    }
    if (includesH2) {
        testImplementation("io.github.thirtyeighttwentysix:volan-dialect-h2")
        testRuntimeOnly("com.h2database:h2:2.5.252")
    }
}

val generateClient = tasks.register<JavaExec>("generateClient") {
    classpath = generator.runtimeClasspath
    mainClass.set("smoke.GenerateClient")
    val schema = layout.projectDirectory.file("schema.volan")
    inputs.file(schema)
    outputs.dir(generatedSources)
    args(schema.asFile.absolutePath, generatedSources.get().asFile.absolutePath)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjdk-release=17", "-Xemit-jvm-type-annotations")
    }
    sourceSets.main { kotlin.srcDir(generatedSources) }
}

tasks.named("compileKotlin") { dependsOn(generateClient) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.test {
    systemProperty("volan.sqlite", includesSqlite.toString())
    systemProperty("volan.h2", includesH2.toString())
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
