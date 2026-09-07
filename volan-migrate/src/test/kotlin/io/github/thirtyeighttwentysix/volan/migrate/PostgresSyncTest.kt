package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.postgres.PostgresDialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("io.github.thirtyeighttwentysix.volan.migrate.Docker#isAvailable")
class PostgresSyncTest {
    private val container = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).apply { start() }
    private val sync = DatabaseSync(PostgresReader(), PostgresDialect)

    @AfterAll
    fun stop() = container.stop()

    @BeforeEach
    fun reset() {
        connect().use { it.createStatement().use { sql -> sql.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public") } }
    }

    @Test
    fun `schema migration database introspection and schema text complete the round trip`() {
        connect().use { connection ->
            val schema = Fixtures.blog()
            sync.push(connection, schema).isEmpty shouldBe false
            val pulled = Fixtures.schema(sync.pull(connection))
            SchemaMapper.map(pulled) shouldBe SchemaMapper.map(schema)
            sync.push(connection, pulled).isEmpty shouldBe true
            MigrationJournal().exists(connection) shouldBe false
        }
    }

    @Test
    fun `push previews warnings and can drop mutually dependent tables in order`() {
        connect().use { connection ->
            sync.push(connection, Fixtures.blog())
            val empty = Fixtures.schema(SchemaWriter.write(DatabaseSchema()))
            shouldThrow<VolanMigrationException> { sync.push(connection, empty) }
            sync.plan(connection, Fixtures.blog()).isEmpty shouldBe true
            sync.push(connection, empty, acceptWarnings = true)
            PostgresReader().read(connection) shouldBe DatabaseSchema()
        }
    }

    @Test
    fun `manual database changes are detected as structural drift`() {
        connect().use { connection ->
            sync.push(connection, Fixtures.blog())
            val expected = PostgresReader().read(connection)
            connection.createStatement().use { it.execute("ALTER TABLE users ADD COLUMN unexpected text") }
            sync.drift(connection, expected).steps.size shouldBe 1
        }
    }

    @Test
    fun `a failed push rolls back all preceding statements and restores auto commit`() {
        connect().use { connection ->
            sync.push(connection, Fixtures.schema(note))
            connection.createStatement().use { it.execute("INSERT INTO \"Note\" VALUES (1)") }
            val changed = note.replace("id Int @id", "id Int @id\n  safe String?\n  required String")
            shouldThrow<VolanMigrationException> { sync.push(connection, Fixtures.schema(changed), acceptWarnings = true) }
            connection.autoCommit shouldBe true
            sync.plan(connection, Fixtures.schema(note)).isEmpty shouldBe true
        }
    }

    @Test
    fun `push never commits a callers transaction`() {
        connect().use { connection ->
            connection.autoCommit = false
            shouldThrow<VolanMigrationException> { sync.push(connection, Fixtures.blog()) }
            connection.rollback()
        }
    }

    @Test
    fun `verification failures outside JDBC also roll back the push`() {
        val faultyReader = object : DatabaseReader {
            var reads = 0

            override fun read(connection: java.sql.Connection): DatabaseSchema {
                check(++reads == 1) { "Injected verification failure" }
                return PostgresReader().read(connection)
            }
        }
        connect().use { connection ->
            shouldThrow<IllegalStateException> {
                DatabaseSync(faultyReader, PostgresDialect).push(connection, Fixtures.schema(note))
            }
            connection.autoCommit shouldBe true
            PostgresReader().read(connection) shouldBe DatabaseSchema()
        }
    }

    @Test
    fun `quoted defaults and enum arrays survive introspection and export`() {
        val schema = note.replace("model Note", "enum Colour {\n RED\n BLUE\n}\nmodel Note")
            .replace("id Int @id", "id Int @id\n  text String @default(\"'a::b'\")\n  colours Colour[] @default([])")
        connect().use { connection ->
            sync.push(connection, Fixtures.schema(schema))
            SchemaMapper.map(Fixtures.schema(sync.pull(connection))) shouldBe SchemaMapper.map(Fixtures.schema(schema))
        }
    }

    @Test
    fun `unsupported indexes are refused rather than exported as ordinary indexes`() {
        val indexes = listOf(
            "CREATE INDEX custom ON \"Note\" (id) WHERE id > 0",
            "CREATE INDEX custom ON \"Note\" (id DESC)",
            "CREATE INDEX custom ON \"Note\" USING hash (id)",
            "CREATE INDEX custom ON \"Note\" ((id + 1))",
        )
        connect().use { connection ->
            sync.push(connection, Fixtures.schema(note))
            indexes.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
                shouldThrow<VolanMigrationException> { sync.pull(connection) }
                connection.createStatement().use { it.execute("DROP INDEX custom") }
            }
        }
    }

    @Test
    fun `an expression beginning with a cast string is preserved as an expression`() {
        connect().use { connection ->
            sync.push(connection, Fixtures.schema(note))
            connection.createStatement().use {
                it.execute("ALTER TABLE \"Note\" ADD COLUMN label text NOT NULL DEFAULT ('left'::text || 'right'::text)")
            }
            val actual = PostgresReader().read(connection)
            val default = actual.table("Note")?.column("label")?.default
            (default is io.github.thirtyeighttwentysix.volan.dialect.ColumnDefault.Expression) shouldBe true
            SchemaMapper.map(Fixtures.schema(sync.pull(connection))) shouldBe actual
        }
    }

    private fun connect() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    private val note = """
        datasource db {
          provider = "postgresql"
          url = env("DATABASE_URL")
        }
        model Note {
          id Int @id
        }
    """.trimIndent()
}
