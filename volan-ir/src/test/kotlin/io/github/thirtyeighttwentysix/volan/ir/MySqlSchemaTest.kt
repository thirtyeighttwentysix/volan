package io.github.thirtyeighttwentysix.volan.ir

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MySqlSchemaTest {
    @Test fun `unsupported storage is rejected for both providers`() {
        for (provider in listOf("mysql", "mariadb")) {
            for (fields in listOf(
                "id Int @id\nvalues String[]",
                "id Int @id\nname String @db.VarChar(100)",
                "id Uuid @id @default(uuid())",
                "id Int\nother Int @default(autoincrement())\n@@id([id,other])",
            )) {
                analyze(provider, fields).errors.map { it.code } shouldContain SemanticCode.UNSUPPORTED_PROVIDER_FEATURE
            }
        }
    }

    @Test fun `standard scalars fulltext and explicit UUID keys are supported`() {
        for (provider in listOf("mysql", "mariadb")) {
            analyze(provider, "id Uuid @id\namount Decimal\nname String\n@@fulltext([name])").hasErrors shouldBe false
            analyze(provider, "id Long @id @default(autoincrement())").hasErrors shouldBe false
        }
    }

    @Test fun `SetDefault is rejected on relations`() {
        for (provider in listOf("mysql", "mariadb")) {
            analyze(
                provider,
                "id Int @id\nparentId Int @default(1)\n" +
                    "parent Item @relation(\"Tree\", fields: [parentId], references: [id], onDelete: SetDefault)\n" +
                    "children Item[] @relation(\"Tree\")",
            ).errors.map { it.code } shouldContain SemanticCode.UNSUPPORTED_PROVIDER_FEATURE
        }
    }

    private fun analyze(provider: String, fields: String) = SchemaLoader.load(
        "mysql.volan",
        "datasource db {\nprovider = \"$provider\"\nurl = env(\"DATABASE_URL\")\n}\nmodel Item {\n$fields\n}",
    )
}
