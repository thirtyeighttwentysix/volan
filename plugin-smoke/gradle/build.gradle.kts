plugins {
    kotlin("jvm") version "2.4.20"
    id("io.github.thirtyeighttwentysix.volan")
}

val volanVersion = providers.gradleProperty("volanVersion").getOrElse("0.1.0-alpha.3")
dependencies {
    implementation("io.github.thirtyeighttwentysix:volan-dialect-h2:$volanVersion")
    runtimeOnly("com.h2database:h2:2.5.252")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("io.github.thirtyeighttwentysix:volan-coroutines:$volanVersion")
    testImplementation("io.github.thirtyeighttwentysix:volan-micrometer:$volanVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.20")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.test { useJUnitPlatform() }
