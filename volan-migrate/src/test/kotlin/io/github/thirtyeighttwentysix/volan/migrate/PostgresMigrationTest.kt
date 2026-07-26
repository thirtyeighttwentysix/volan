package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.postgres.PostgresDialect
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/** Whether there is a Docker daemon to run PostgreSQL in. */
object Docker {
    @JvmStatic
    fun isAvailable(): Boolean = DockerClientFactory.instance().isDockerAvailable
}

/**
 * The migrations Volan writes, run by the database they were written for.
 *
 * A plan that renders to plausible-looking SQL is worth nothing until a database accepts it. This is
 * where that is settled, and where a schema applied twice is shown to produce no second migration.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("io.github.thirtyeighttwentysix.volan.migrate.Docker#isAvailable")
class PostgresMigrationTest {
    private val container = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).apply { start() }

    @AfterAll
    fun stop() {
        container.stop()
    }

    @BeforeEach
    fun resetSchema() {
        connect().use { it.createStatement().use { statement -> statement.execute("drop schema public cascade; create schema public;") } }
    }

    @Test
    fun `the reference schema applies to an empty database`() {
        val plan = SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(Fixtures.blog()))

        apply(plan)

        tableNames() shouldBe listOf("Post", "Profile", "Tag", "_PostTags", "post_comments", "users")
    }

    @Test
    fun `every kind of column the schema language has survives being created`() {
        apply(SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(Fixtures.schema(EVERY_TYPE))))

        tableNames() shouldBe listOf("Everything")
    }

    @Test
    fun `adding a column, a constraint and an index applies on top of what is already there`() {
        val before = SchemaMapper.map(Fixtures.schema(BEFORE))
        val after = SchemaMapper.map(Fixtures.schema(AFTER))
        apply(SchemaDiffer.diff(DatabaseSchema(), before))

        apply(SchemaDiffer.diff(before, after))

        columnNames("Note") shouldBe listOf("id", "title", "slug")
    }

    @Test
    fun `dropping a column and its index applies without leaving either behind`() {
        val before = SchemaMapper.map(Fixtures.schema(AFTER))
        val after = SchemaMapper.map(Fixtures.schema(BEFORE))
        apply(SchemaDiffer.diff(DatabaseSchema(), before))

        apply(SchemaDiffer.diff(before, after))

        columnNames("Note") shouldBe listOf("id", "title")
    }

    @Test
    fun `a value added to an enum reaches the database`() {
        apply(SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(Fixtures.schema(TWO_ROLES))))

        apply(SchemaDiffer.diff(SchemaMapper.map(Fixtures.schema(TWO_ROLES)), SchemaMapper.map(Fixtures.schema(THREE_ROLES))))

        enumValues("Role") shouldBe listOf("USER", "ADMIN", "OWNER")
    }

    private fun apply(plan: MigrationPlan) {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                plan.render(PostgresDialect).forEach { statement.execute(it) }
            }
        }
    }

    private fun connect(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    private fun tableNames(): List<String> = read(
        "select table_name from information_schema.tables where table_schema = 'public' order by table_name",
    )

    private fun columnNames(table: String): List<String> = read(
        "select column_name from information_schema.columns where table_name = '$table' order by ordinal_position",
    )

    private fun enumValues(type: String): List<String> = read(
        "select enumlabel from pg_enum join pg_type on pg_type.oid = enumtypid where typname = '$type' order by enumsortorder",
    )

    private fun read(sql: String): List<String> = connect().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                val values = ArrayList<String>()
                while (result.next()) values.add(result.getString(1))
                values
            }
        }
    }

    private companion object {
        private const val HEAD = """
            datasource db {
              provider = "postgresql"
              url      = env("DATABASE_URL")
            }
        """

        private val EVERY_TYPE = HEAD.trimIndent() + """
            model Everything {
              id       Int      @id @default(autoincrement())
              text     String
              small    Int      @default(0)
              big      Long     @default(0)
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
              optional String?
              tags     String[]
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
              slug  String @unique

              @@index([title])
            }
        """.trimIndent()

        private val TWO_ROLES = HEAD.trimIndent() + """
            enum Role {
              USER
              ADMIN
            }

            model Account {
              id   Int  @id @default(autoincrement())
              role Role @default(USER)
            }
        """.trimIndent()

        private val THREE_ROLES = HEAD.trimIndent() + """
            enum Role {
              USER
              ADMIN
              OWNER
            }

            model Account {
              id   Int  @id @default(autoincrement())
              role Role @default(USER)
            }
        """.trimIndent()
    }
}
