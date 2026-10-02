plugins {
    id("volan.kotlin-library")
    application
}

description = "The Volan database schema command line."

dependencies {
    implementation(project(":volan-migrate"))
    implementation(project(":volan-dialect-postgres"))
    implementation(project(":volan-dialect-sqlite"))
    implementation(project(":volan-dialect-h2"))
    implementation(project(":volan-dialect-mysql"))
    implementation(libs.clikt)
    runtimeOnly(libs.jdbc.postgres)
    runtimeOnly(libs.jdbc.sqlite)
    runtimeOnly(libs.jdbc.h2)
    runtimeOnly(libs.jdbc.mysql)
    runtimeOnly(libs.jdbc.mariadb)
}

application {
    mainClass.set("io.github.thirtyeighttwentysix.volan.cli.MainKt")
    applicationName = "volan"
    // The terminal and SQLite drivers use JNI. Declare that access in both generated launchers.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    dependsOn(tasks.installDist)
    jvmArgs(application.applicationDefaultJvmArgs)
    systemProperty("volan.cli.installDir", layout.buildDirectory.dir("install/volan").get().asFile.absolutePath)
}
