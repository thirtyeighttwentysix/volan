package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnChange
import io.github.thirtyeighttwentysix.volan.dialect.DdlStatement
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SchemaDifferTest {
    private val blog = SchemaMapper.map(Fixtures.blog())

    @Test
    fun `a database that already matches the schema needs no migration`() {
        SchemaDiffer.diff(blog, blog).isEmpty shouldBe true
    }

    @Test
    fun `an empty database is filled in an order that is valid at every step`() {
        val plan = SchemaDiffer.diff(DatabaseSchema(), blog)
        val phases = plan.steps.map { it.statement::class.simpleName }

        phases.toSet() shouldBe setOf("CreateEnum", "CreateTable", "AddUnique", "CreateIndex", "AddForeignKey")
        phases.lastIndexOf("CreateEnum") shouldBeLessThan phases.indexOf("CreateTable")
        phases.lastIndexOf("CreateTable") shouldBeLessThan phases.indexOf("AddUnique")
        phases.lastIndexOf("CreateTable") shouldBeLessThan phases.indexOf("CreateIndex")
        phases.lastIndexOf("CreateIndex") shouldBeLessThan phases.indexOf("AddForeignKey")
        plan.isDestructive shouldBe false
    }

    @Test
    fun `a new model becomes one table and the keys that belong to it`() {
        val before = SchemaMapper.map(Fixtures.schema(ONE_MODEL))
        val after = SchemaMapper.map(Fixtures.schema(TWO_MODELS))
        val plan = SchemaDiffer.diff(before, after)

        plan.steps.map { it.statement }.filterIsInstance<DdlStatement.CreateTable>().single().table shouldBe "Author"
        plan.warnings.shouldBeEmpty()
    }

    @Test
    fun `dropping a model warns about the rows that go with it`() {
        val plan = SchemaDiffer.diff(SchemaMapper.map(Fixtures.schema(TWO_MODELS)), SchemaMapper.map(Fixtures.schema(ONE_MODEL)))

        plan.steps.map { it.statement }.filterIsInstance<DdlStatement.DropTable>().single().table shouldBe "Author"
        plan.warnings.single().shouldContain("every row in it with it")
    }

    @Test
    fun `an added column that is required and undefaulted warns before the database refuses it`() {
        val plan = SchemaDiffer.diff(SchemaMapper.map(Fixtures.schema(ONE_MODEL)), SchemaMapper.map(Fixtures.schema(ONE_MODEL_PLUS)))

        val added = plan.steps.single { it.statement is DdlStatement.AddColumn }
        (added.statement as DdlStatement.AddColumn).column.name shouldBe "title"
        added.warning.shouldContain("fails if `Note` already has rows")
    }

    @Test
    fun `an added column that may hold null is added without a word`() {
        val plan = SchemaDiffer.diff(
            SchemaMapper.map(Fixtures.schema(ONE_MODEL)),
            SchemaMapper.map(Fixtures.schema(ONE_MODEL_OPTIONAL)),
        )

        plan.steps.single().statement.shouldBeInstanceOf<DdlStatement.AddColumn>()
        plan.warnings.shouldBeEmpty()
    }

    @Test
    fun `making a column required warns, making it optional does not`() {
        val optional = SchemaMapper.map(Fixtures.schema(ONE_MODEL_OPTIONAL))
        val required = SchemaMapper.map(Fixtures.schema(ONE_MODEL_REQUIRED))

        val tightened = SchemaDiffer.diff(optional, required).steps.single()
        (tightened.statement as DdlStatement.AlterColumn).change shouldBe ColumnChange.Nullability(false)
        tightened.warning.shouldContain("if any row holds null there")

        SchemaDiffer.diff(required, optional).steps.single().warning shouldBe null
    }

    @Test
    fun `a changed type warns about the values that will not fit the new one`() {
        val plan = SchemaDiffer.diff(
            SchemaMapper.map(Fixtures.schema(ONE_MODEL_OPTIONAL)),
            SchemaMapper.map(Fixtures.schema(ONE_MODEL_RETYPED)),
        )

        plan.steps.single { it.statement is DdlStatement.AlterColumn }.warning.shouldContain("do not fit the new one")
    }

    @Test
    fun `a value added to an enum is added after the ones already there`() {
        val before = DatabaseSchema(enums = listOf(EnumDefinition("Role", listOf("USER", "ADMIN"))))
        val after = DatabaseSchema(enums = listOf(EnumDefinition("Role", listOf("USER", "ADMIN", "OWNER", "GUEST"))))

        SchemaDiffer.diff(before, after).steps.map { it.statement } shouldContainExactly listOf(
            DdlStatement.AddEnumValue("Role", "OWNER", "ADMIN"),
            DdlStatement.AddEnumValue("Role", "GUEST", "OWNER"),
        )
    }

    @Test
    fun `a value taken out of an enum is refused, because rows may still hold it`() {
        val before = DatabaseSchema(enums = listOf(EnumDefinition("Role", listOf("USER", "ADMIN"))))
        val after = DatabaseSchema(enums = listOf(EnumDefinition("Role", listOf("USER"))))

        val failure = shouldThrow<VolanMigrationException> { SchemaDiffer.diff(before, after) }
        failure.message.shouldContain("write the migration that moves those rows first")
    }

    @Test
    fun `reordering the values of an enum is refused, because no database can do it`() {
        val before = DatabaseSchema(enums = listOf(EnumDefinition("Role", listOf("USER", "ADMIN"))))
        val after = DatabaseSchema(enums = listOf(EnumDefinition("Role", listOf("ADMIN", "USER"))))

        shouldThrow<VolanMigrationException> { SchemaDiffer.diff(before, after) }
            .message.shouldContain("no database can reorder them")
    }

    @Test
    fun `a new unique constraint warns that the rows already there may not be unique`() {
        val plan = SchemaDiffer.diff(
            SchemaMapper.map(Fixtures.schema(ONE_MODEL_OPTIONAL)),
            SchemaMapper.map(Fixtures.schema(ONE_MODEL_UNIQUE)),
        )

        plan.steps.single { it.statement is DdlStatement.AddUnique }.warning
            .shouldContain("fails if two rows already share those values")
    }

    private companion object {
        private const val HEAD = """
            datasource db {
              provider = "postgresql"
              url      = env("DATABASE_URL")
            }
        """

        private val ONE_MODEL = HEAD.trimIndent() + """
            model Note {
              id Int @id @default(autoincrement())
            }
        """.trimIndent()

        private val ONE_MODEL_PLUS = HEAD.trimIndent() + """
            model Note {
              id    Int    @id @default(autoincrement())
              title String
            }
        """.trimIndent()

        private val ONE_MODEL_OPTIONAL = HEAD.trimIndent() + """
            model Note {
              id    Int     @id @default(autoincrement())
              title String?
            }
        """.trimIndent()

        private val ONE_MODEL_REQUIRED = ONE_MODEL_PLUS

        private val ONE_MODEL_RETYPED = HEAD.trimIndent() + """
            model Note {
              id    Int  @id @default(autoincrement())
              title Int?
            }
        """.trimIndent()

        private val ONE_MODEL_UNIQUE = HEAD.trimIndent() + """
            model Note {
              id    Int     @id @default(autoincrement())
              title String? @unique
            }
        """.trimIndent()

        private val TWO_MODELS = HEAD.trimIndent() + """
            model Author {
              id Int @id @default(autoincrement())
            }

            model Note {
              id Int @id @default(autoincrement())
            }
        """.trimIndent()
    }
}
