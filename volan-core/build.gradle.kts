plugins {
    id("volan.kotlin-library")
}

description = "Foundation types shared by every Volan module: the exception hierarchy root."

dependencies {
    api(libs.jspecify)
}
