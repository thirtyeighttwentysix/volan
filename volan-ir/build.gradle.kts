plugins {
    id("volan.kotlin-library")
}

description = "Semantic analysis and the normalized intermediate representation of a Volan schema."

dependencies {
    api(project(":volan-schema"))
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
