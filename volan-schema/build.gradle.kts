plugins {
    id("volan.published-library")
}

description = "Lexer, parser, AST and formatter for the schema.volan schema language."

dependencies {
    api(project(":volan-core"))

    testImplementation(libs.kotest.property)
}

kover {
    reports {
        verify {
            rule {
                bound { minValue = 85 }
            }
        }
    }
}
