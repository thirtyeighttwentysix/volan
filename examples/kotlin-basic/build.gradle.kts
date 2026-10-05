plugins {
    kotlin("jvm") version "2.4.20"
    id("io.github.thirtyeighttwentysix.volan")
    application
}
val volanVersion = providers.gradleProperty("volanVersion").getOrElse("1.0.0")
dependencies {
    implementation(platform("io.github.thirtyeighttwentysix:volan-bom:$volanVersion"))
    implementation("io.github.thirtyeighttwentysix:volan-dialect-h2")
    implementation("io.github.thirtyeighttwentysix:volan-migrate")
    runtimeOnly("com.h2database:h2:2.5.252")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.20")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.test { useJUnitPlatform() }
application { mainClass.set("example.MainKt") }
