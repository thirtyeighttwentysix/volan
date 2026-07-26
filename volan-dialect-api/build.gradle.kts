plugins {
    id("volan.kotlin-library")
}

description = "Dialect-independent SQL model and the SPI every Volan dialect implements."

dependencies {
    api(libs.jspecify)
}
