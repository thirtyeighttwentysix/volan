plugins {
    id("volan.kotlin-library")
}

description = "Lexer, parser and AST for the schema.volan schema language."

dependencies {
    testImplementation(libs.kotest.property)
}
