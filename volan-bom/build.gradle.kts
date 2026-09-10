import com.vanniktech.maven.publish.JavaPlatform

plugins {
    `java-platform`
    id("volan.publishing")
}

mavenPublishing { configure(JavaPlatform()) }

description = "Bill of materials pinning every Volan module to a single version."

dependencies {
    constraints {
        api(project(":volan-core"))
        api(project(":volan-schema"))
        api(project(":volan-ir"))
        api(project(":volan-codegen"))
        api(project(":volan-dialect-api"))
        api(project(":volan-dialect-postgres"))
        api(project(":volan-runtime"))
        api(project(":volan-migrate"))
    }
}
