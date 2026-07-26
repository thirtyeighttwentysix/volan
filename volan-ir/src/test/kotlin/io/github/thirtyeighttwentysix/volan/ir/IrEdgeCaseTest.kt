package io.github.thirtyeighttwentysix.volan.ir

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The corners of the schema language: every kind of default, `@ignore`, named constraints, and a model
 * that deliberately has no primary key.
 */
class IrEdgeCaseTest {
    private val result = SchemaLoader.load(Fixtures.source("edge.volan"))
    private val schema = result.schemaOrThrow()

    @Test
    fun `the edge fixture analyses with only the hardcoded-url warning`() {
        withClue(result.render()) {
            result.errors.shouldBeEmpty()
            result.warnings.map { it.code } shouldContainExactly listOf(SemanticCode.CONNECTION_URL_IN_SCHEMA)
        }
    }

    @Test
    fun `the ir matches its snapshot`() {
        IrPrinter.print(schema) shouldBe Fixtures.read("edge.ir.txt")
    }

    @Test
    fun `a url written into the schema is still resolved`() {
        schema.datasource.url shouldBe ConnectionUrl.Literal("file:./dev.db")
        schema.datasource.provider shouldBe Provider.SQLITE
    }

    @Test
    fun `every kind of generated default is recognised`() {
        val account = schema.model("Account").shouldNotBeNull()
        account.field("key").shouldNotBeNull().default shouldBe DefaultValue.Uuid
        account.field("tenant").shouldNotBeNull().default shouldBe DefaultValue.Cuid
        account.field("balance").shouldNotBeNull().default shouldBe DefaultValue.NumberValue("0.0")
        account.field("labels").shouldNotBeNull().default shouldBe DefaultValue.EmptyList
        account.field("token").shouldNotBeNull().default shouldBe DefaultValue.DatabaseGenerated("gen_random_uuid()")
        account.field("legacy").shouldNotBeNull().default shouldBe DefaultValue.DatabaseGenerated(null)
    }

    @Test
    fun `constraints keep the names the schema asked for`() {
        val account = schema.model("Account").shouldNotBeNull()
        account.primaryKey.shouldNotBeNull().fields shouldContainExactly listOf("key", "tenant")
        account.primaryKey.shouldNotBeNull().dbName shouldBe "account_pk"
        account.uniques.single().dbName shouldBe "account_tenant_balance"
        account.indexes.single().dbName shouldBe "account_balance_idx"
    }

    @Test
    fun `an ignored field stays in the ir, marked`() {
        schema.model("Account").shouldNotBeNull().field("note").shouldNotBeNull().isIgnored shouldBe true
    }

    @Test
    fun `an ignored model may have no primary key at all`() {
        val legacy = schema.model("LegacyAudit").shouldNotBeNull()
        legacy.isIgnored shouldBe true
        legacy.primaryKey shouldBe null
        legacy.primaryKeyFields.shouldBeEmpty()
        legacy.documentation shouldBe "A table Volan does not generate a client for."
    }

    @Test
    fun `a model that is not ignored still needs a primary key`() {
        val broken = SchemaLoader.load(
            "schema.volan",
            "datasource db {\n  provider = \"sqlite\"\n  url = env(\"URL\")\n}\n\nmodel Audit {\n  body String\n}\n",
        )
        broken.diagnostics.map { it.code } shouldContain SemanticCode.MISSING_PRIMARY_KEY
    }
}
