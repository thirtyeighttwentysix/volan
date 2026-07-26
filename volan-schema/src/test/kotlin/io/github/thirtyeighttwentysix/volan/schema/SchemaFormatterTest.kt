package io.github.thirtyeighttwentysix.volan.schema

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SchemaFormatterTest {
    @Test
    fun `formats the reference schema into its canonical form`() {
        SchemaFormatter.format(Fixtures.source("reference.volan")) shouldBe Fixtures.read("reference.formatted.volan")
    }

    @Test
    fun `formatting is idempotent`() {
        val once = SchemaFormatter.format(Fixtures.source("reference.volan"))
        SchemaFormatter.format("schema.volan", once) shouldBe once
    }

    @Test
    fun `normalises indentation, alignment and spacing`() {
        val messy = "model    User{\n" +
            "\tid Int   @id    @default( autoincrement( ) )\n" +
            "     email String @unique\n" +
            "}"
        SchemaFormatter.format("schema.volan", messy) shouldBe
            """
            model User {
              id    Int    @id @default(autoincrement())
              email String @unique
            }

            """.trimIndent()
    }

    @Test
    fun `keeps comments where the author put them`() {
        val schema = """
            // the account of a person using the application
            /// Every human who can sign in.
            model User {
              // the surrogate key
              id    Int    @id
              email String @unique // login
            }
        """.trimIndent() + "\n"
        SchemaFormatter.format("schema.volan", schema) shouldBe schema
    }

    @Test
    fun `keeps the blank lines the author used to group fields`() {
        val schema = """
            model User {
              id    Int    @id

              email String @unique
              name  String

              @@map("users")
            }
        """.trimIndent() + "\n"
        SchemaFormatter.format("schema.volan", schema) shouldBe schema
    }

    @Test
    fun `puts exactly one blank line between declarations`() {
        val schema = "enum Role {\n  USER\n}\n\n\n\n\nmodel User {\n  role Role\n}\n"
        SchemaFormatter.format("schema.volan", schema) shouldBe
            "enum Role {\n  USER\n}\n\nmodel User {\n  role Role\n}\n"
    }

    @Test
    fun `keeps comments that trail the last declaration`() {
        val schema = "model User {\n  id Int @id\n}\n\n// nothing follows\n"
        SchemaFormatter.format("schema.volan", schema) shouldBe schema
    }

    @Test
    fun `drops empty argument lists`() {
        val schema = "model User {\n  id Int @id()\n}\n"
        SchemaFormatter.format("schema.volan", schema) shouldBe "model User {\n  id Int @id\n}\n"
    }

    @Test
    fun `refuses to format a schema it cannot parse`() {
        val thrown = runCatching { SchemaFormatter.format("schema.volan", "model User {\n  email @unique\n}\n") }
            .exceptionOrNull()
        (thrown is VolanSchemaException) shouldBe true
    }
}
