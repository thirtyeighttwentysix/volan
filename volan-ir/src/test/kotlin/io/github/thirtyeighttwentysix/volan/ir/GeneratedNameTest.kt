package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.SourceSpan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class GeneratedNameTest {
    private val datasource = """
        datasource db {
          provider = "sqlite"
          url = env("DATABASE_URL")
        }
    """.trimIndent()
    private val generator = """
        generator client {
          provider = "volan-kotlin"
          package = "com.example"
        }
    """.trimIndent()

    @Test
    fun `a generated count field points to its name and suggests preserving the column`() {
        val text = schema("count Int", generator)
        val result = SchemaLoader.load("schema.volan", text)
        result.schema shouldBe null
        val error = result.errors.single()
        error.code.id shouldBe "E0236"
        val start = text.indexOf("count Int")
        error.span shouldBe SourceSpan(start, start + "count".length)
        result.render() shouldContain "`Record.count` conflicts with the generated `groupBy` row count"
        result.render() shouldContain "@map(\"count\")"
    }

    @Test
    fun `mapping or omitting the field from generation preserves a database column called count`() {
        val schemas = listOf(
            schema("itemCount Int @map(\"count\")", generator),
            schema("count Int @ignore", generator),
            schema("count Int", generator, ignored = true),
            schema("count Int", ""),
        )
        schemas.forEach { text ->
            val field = SchemaLoader.load("schema.volan", text).schemaOrThrow().models.single().fields.last()
            field.dbName shouldBe "count"
        }
    }

    private fun schema(field: String, generator: String, ignored: Boolean = false): String = """
        $datasource
        $generator
        model Record {
          id Int @id
          $field
          ${if (ignored) "@@ignore" else ""}
        }
    """.trimIndent()
}
