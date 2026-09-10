plugins {
    id("volan.published-library")
}

description = "Foundation types shared by every Volan module: the exception hierarchy root."

dependencies {
    api(libs.jspecify)
}
