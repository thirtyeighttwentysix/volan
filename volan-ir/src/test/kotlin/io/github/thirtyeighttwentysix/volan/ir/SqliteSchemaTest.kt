package io.github.thirtyeighttwentysix.volan.ir

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SqliteSchemaTest {
    @Test
    fun `decimal storage cannot silently lose precision or use textual ordering`() {
        val result = analyze("id Int @id\namount Decimal")
        result.errors.map { it.code } shouldContain SemanticCode.UNSUPPORTED_PROVIDER_FEATURE
        result.render() shouldContain "fixed scale"
    }

    @Test
    fun `scalar arrays are rejected before generation`() {
        val result = analyze("id Int @id\ntags String[]")
        result.errors.map { it.code } shouldContain SemanticCode.UNSUPPORTED_PROVIDER_FEATURE
        result.render() shouldContain "scalar list"
    }

    @Test
    fun `native type overrides have a provider specific explanation`() {
        val result = analyze("id Int @id\nname String @db.VarChar(200)")
        result.render() shouldContain "remove the @db attribute"
        result.errors.map { it.code } shouldContain SemanticCode.UNSUPPORTED_PROVIDER_FEATURE
    }

    @Test
    fun `SQLite autoincrement cannot be on a non primary or composite key field`() {
        analyze("id Int @id\nsequence Int @default(autoincrement())").hasErrors shouldBe true
        analyze("id Int @default(autoincrement())\nother Int\n@@id([id, other])").hasErrors shouldBe true
        analyze("id Long @id @default(autoincrement())").hasErrors shouldBe false
    }

    @Test
    fun `fulltext requires a virtual table and is not an ordinary index`() {
        val result = analyze("id Int @id\nname String\n@@fulltext([name])")
        result.render() shouldContain "FTS virtual tables"
        result.errors.map { it.code } shouldContain SemanticCode.UNSUPPORTED_PROVIDER_FEATURE
    }

    @Test
    fun `PostgreSQL array fields are unaffected`() {
        SchemaLoader.load("schema.volan", schema("id Int @id\ntags String[]").replace("sqlite", "postgresql"))
            .hasErrors shouldBe false
    }

    private fun analyze(fields: String) = SchemaLoader.load("sqlite.volan", schema(fields))

    private fun schema(fields: String) = """
        datasource db {
          provider = "sqlite"
          url = env("DATABASE_URL")
        }
        model User {
          $fields
        }
    """.trimIndent()
}
