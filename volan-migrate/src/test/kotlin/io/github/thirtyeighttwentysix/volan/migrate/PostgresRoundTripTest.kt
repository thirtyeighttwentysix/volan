package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.postgres.PostgresDialect
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/**
 * A schema, the database it creates, and the schema read back out of it.
 *
 * This is what makes a migration tool trustworthy rather than merely plausible: applying a schema and
 * reading the result has to produce what was applied. Anything it does not produce is a difference
 * Volan would try to apply again, forever.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("io.github.thirtyeighttwentysix.volan.migrate.Docker#isAvailable")
class PostgresRoundTripTest {
    private val container = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).apply { start() }
    private val reader = PostgresReader()

    @AfterAll
    fun stop() {
        container.stop()
    }

    @BeforeEach
    fun resetSchema() {
        connect().use { it.createStatement().use { statement -> statement.execute("drop schema public cascade; create schema public;") } }
    }

    @Test
    fun `an empty database reads back as an empty schema`() {
        read().tables.shouldBeEmpty()
        read().enums.shouldBeEmpty()
    }

    @Test
    fun `the reference schema survives being applied and read back`() {
        val wanted = SchemaMapper.map(Fixtures.blog())

        apply(wanted)

        read() shouldBe wanted
    }

    @Test
    fun `applying the reference schema twice asks for nothing the second time`() {
        val wanted = SchemaMapper.map(Fixtures.blog())
        apply(wanted)

        SchemaDiffer.diff(read(), wanted).isEmpty shouldBe true
    }

    @Test
    fun `every type the schema language has survives the round trip`() {
        val wanted = SchemaMapper.map(Fixtures.schema(EVERY_TYPE))

        apply(wanted)

        read() shouldBe wanted
    }

    @Test
    fun `a native type survives the round trip under the name the schema wrote`() {
        val wanted = SchemaMapper.map(Fixtures.schema(NATIVE_TYPES))

        apply(wanted)

        read() shouldBe wanted
    }

    @Test
    fun `a db type that only repeats what the field already said is not a difference`() {
        val plain = SchemaMapper.map(Fixtures.schema(PLAIN))
        val spelled = SchemaMapper.map(Fixtures.schema(SPELLED_OUT))

        plain shouldBe spelled
    }

    @Test
    fun `a schema changed after it was applied reads back as the change and nothing else`() {
        apply(SchemaMapper.map(Fixtures.schema(BEFORE)))
        val wanted = SchemaMapper.map(Fixtures.schema(AFTER))

        val plan = SchemaDiffer.diff(read(), wanted)
        plan.steps.map { it.statement::class.simpleName } shouldContainExactly listOf("AddColumn", "AddUnique", "CreateIndex")

        apply(plan)
        read() shouldBe wanted
    }

    @Test
    fun `the history table is Volan's own bookkeeping and not part of the schema`() {
        connect().use { MigrationJournal().ensure(it) }

        read().tables.shouldBeEmpty()
    }

    private fun apply(wanted: DatabaseSchema) = apply(SchemaDiffer.diff(read(), wanted))

    private fun apply(plan: MigrationPlan) {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                plan.render(PostgresDialect).forEach { statement.execute(it) }
            }
        }
    }

    private fun read(): DatabaseSchema = connect().use { reader.read(it) }

    private fun connect(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    private companion object {
        private const val HEAD = """
            datasource db {
              provider = "postgresql"
              url      = env("DATABASE_URL")
            }
        """

        private val EVERY_TYPE = HEAD.trimIndent() + """
            enum Colour {
              RED
              GREEN
            }

            model Everything {
              id       Int      @id @default(autoincrement())
              big      Long     @unique
              text     String
              small    Int      @default(0)
              single   Float    @default(0)
              wide     Double   @default(0)
              exact    Decimal
              flag     Boolean  @default(false)
              moment   DateTime @default(now())
              day      Date
              clock    Time
              document Json
              blob     Bytes
              key      Uuid     @default(uuid())
              colour   Colour   @default(RED)
              optional String?
              tags     String[]

              @@index([text, small])
            }
        """.trimIndent()

        private val NATIVE_TYPES = HEAD.trimIndent() + """
            model Sized {
              id      Int      @id @default(autoincrement())
              short   String   @db.VarChar(64)
              fixed   String   @db.Char(8)
              tiny    Int      @db.SmallInt
              money   Decimal  @db.Decimal(12, 2)
              precise DateTime @db.Timestamptz(6)
              raw     Json     @db.Json
            }
        """.trimIndent()

        private val PLAIN = HEAD.trimIndent() + """
            model Note {
              id   Int    @id @default(autoincrement())
              body String
            }
        """.trimIndent()

        private val SPELLED_OUT = HEAD.trimIndent() + """
            model Note {
              id   Int    @id @default(autoincrement())
              body String @db.Text
            }
        """.trimIndent()

        private val BEFORE = HEAD.trimIndent() + """
            model Note {
              id    Int    @id @default(autoincrement())
              title String
            }
        """.trimIndent()

        private val AFTER = HEAD.trimIndent() + """
            model Note {
              id    Int    @id @default(autoincrement())
              title String
              slug  String @unique @default("")

              @@index([title])
            }
        """.trimIndent()
    }
}
