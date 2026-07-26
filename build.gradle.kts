plugins {
    alias(libs.plugins.kover)
}

description = "Volan — a schema-first, code-generating ORM for the JVM."

dependencies {
    kover(project(":volan-core"))
    kover(project(":volan-schema"))
    kover(project(":volan-ir"))
    kover(project(":volan-codegen"))
    kover(project(":volan-dialect-api"))
    kover(project(":volan-dialect-postgres"))
    kover(project(":volan-dialect-mysql"))
    kover(project(":volan-dialect-sqlite"))
    kover(project(":volan-dialect-h2"))
    kover(project(":volan-runtime"))
    kover(project(":volan-migrate"))
    kover(project(":volan-coroutines"))
}

kover {
    reports {
        total {
            html { onCheck = false }
            xml { onCheck = false }
        }
    }
}
