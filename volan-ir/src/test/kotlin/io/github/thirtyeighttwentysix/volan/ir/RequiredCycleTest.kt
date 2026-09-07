package io.github.thirtyeighttwentysix.volan.ir

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RequiredCycleTest {
    @Test
    fun `mutually required foreign keys cannot be inserted`() {
        val result = SchemaLoader.load("cycle.volan", schema)
        result.errors.map { it.code } shouldContain SemanticCode.UNSATISFIABLE_REQUIRED_RELATION
    }

    @Test
    fun `one optional edge breaks the required cycle`() {
        SchemaLoader.load("cycle.volan", schema.replace("aId Int", "aId Int?").replace("a A @relation", "a A? @relation"))
            .hasErrors shouldBe false
    }

    private val schema = """
        datasource db {
          provider = "postgresql"
          url = env("DATABASE_URL")
        }
        model A {
          id Int @id
          bId Int
          b B @relation("AB", fields: [bId], references: [id])
          bs B[] @relation("BA")
        }
        model B {
          id Int @id
          aId Int
          a A @relation("BA", fields: [aId], references: [id])
          as A[] @relation("AB")
        }
    """.trimIndent()
}
