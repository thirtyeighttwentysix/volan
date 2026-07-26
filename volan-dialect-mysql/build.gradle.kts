plugins {
    id("volan.kotlin-library")
}

description = "MySQL and MariaDB dialect for Volan."

dependencies {
    api(project(":volan-dialect-api"))
    compileOnly(libs.jdbc.mysql)

    testImplementation(libs.jdbc.mysql)
    testImplementation(libs.jdbc.mariadb)
}
