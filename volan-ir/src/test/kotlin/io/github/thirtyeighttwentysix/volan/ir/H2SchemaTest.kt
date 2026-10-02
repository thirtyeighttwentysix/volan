package io.github.thirtyeighttwentysix.volan.ir

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class H2SchemaTest {
    @Test
    fun `native overrides fail before code generation`() {
        val result = analyze("id Int @id\nname String @db.VarChar(100)")
        result.errors.map { it.code } shouldContain SemanticCode.UNSUPPORTED_PROVIDER_FEATURE
        result.render() shouldContain "remove the @db attribute"
    }

    @Test
    fun `fulltext tables require explicit management`() {
        analyze("id Int @id\nname String\n@@fulltext([name])").errors.map { it.code } shouldContain
            SemanticCode.UNSUPPORTED_PROVIDER_FEATURE
    }

    @Test
    fun `decimal arrays and composite identity keys are supported`() {
        analyze("id Long @default(autoincrement())\nother Int\namount Decimal\nwords String[]\n@@id([id, other])")
            .hasErrors shouldBe false
    }

    private fun analyze(fields: String) = SchemaLoader.load(
        "h2.volan",
        """
        datasource db {
          provider = "h2"
          url = env("DATABASE_URL")
        }
        model Item {
          $fields
        }
        """.trimIndent(),
    )
}
