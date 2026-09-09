plugins {
    java
}

description = "Java-only compilation, behavior and signature checks of the generated client."

sourceSets.test { resources.srcDir("../codegen-verify/src/test/resources") }

dependencies {
    testImplementation(project(":codegen-verify"))
    testImplementation(project(":volan-runtime"))
    testImplementation(project(":volan-dialect-postgres"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgres)
    testRuntimeOnly(libs.jdbc.postgres)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

val javaSignatureCheck by tasks.registering(Test::class) {
    description = "Rejects Kotlin-only types in the Java-visible generated API, including generic arguments."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("verify.JavaSignatureTest") }
}

tasks.check { dependsOn(javaSignatureCheck) }

val runtimeApi = listOf("volan-core", "volan-runtime", "volan-dialect-api")
val javaAbiCheck = tasks.register("javaAbiCheck") {
    group = "verification"
    description = "Checks the published runtime ABI for Kotlin-only types."
    val dumps = runtimeApi.map { layout.projectDirectory.file("../$it/api/$it.api") }
    inputs.files(dumps)
    dependsOn(runtimeApi.map { ":$it:apiCheck" })
    doLast {
        val forbidden = dumps.flatMap { dump ->
            dump.asFile.readLines().filter { line ->
                "kotlin/" in line && " synthetic " !in line &&
                    line.trim() != "public static fun getEntries ()Lkotlin/enums/EnumEntries;"
            }.map { "${dump.asFile.name}: $it" }
        }
        check(forbidden.isEmpty()) { forbidden.joinToString("\n") }
    }
}

javaSignatureCheck { dependsOn(javaAbiCheck) }
