plugins {
    `java-platform`
}

description = "Bill of materials pinning every Volan module to a single version."

dependencies {
    constraints {
        api(project(":volan-schema"))
        api(project(":volan-ir"))
        api(project(":volan-codegen"))
        api(project(":volan-dialect-api"))
        api(project(":volan-dialect-postgres"))
        api(project(":volan-dialect-mysql"))
        api(project(":volan-dialect-sqlite"))
        api(project(":volan-dialect-h2"))
        api(project(":volan-runtime"))
        api(project(":volan-migrate"))
        api(project(":volan-coroutines"))
    }
}
