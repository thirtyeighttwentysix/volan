package io.github.thirtyeighttwentysix.volan.migrate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The history a database keeps of its own migrations, kept by a real database.
 *
 * What is being checked here is not that statements run — the other suite settles that — but that
 * Volan can tell a database that has run a migration from one that has not, and can tell either from
 * one whose past has changed underneath it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("io.github.thirtyeighttwentysix.volan.migrate.Docker#isAvailable")
class PostgresMigratorTest {
    private val container = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).apply { start() }
    private val clock = Clock.fixed(Instant.parse("2026-07-27T09:30:00Z"), ZoneOffset.UTC)

    @TempDir
    lateinit var root: Path

    @AfterAll
    fun stop() {
        container.stop()
    }

    @BeforeEach
    fun resetSchema() {
        connect().use { it.createStatement().use { statement -> statement.execute("drop schema public cascade; create schema public;") } }
    }

    @Test
    fun `a fresh database has everything to do and no history to show`() {
        write("20260101000000_first", "CREATE TABLE \"Note\" (\"id\" integer NOT NULL);")

        val status = connect().use { migrator().status(it) }

        status.pending.map { it.id } shouldContainExactly listOf("20260101000000_first")
        status.applied.shouldBeEmpty()
        status.isUpToDate shouldBe false
    }

    @Test
    fun `applying runs each migration once and records that it did`() {
        write("20260101000000_first", "CREATE TABLE \"Note\" (\"id\" integer NOT NULL);")
        write("20260102000000_second", "ALTER TABLE \"Note\" ADD COLUMN \"title\" text;")

        connect().use { migrator().apply(it) }
        val second = connect().use { migrator().apply(it) }

        second.shouldBeEmpty()
        connect().use { migrator().status(it) }.applied.map { it.id } shouldContainExactly
            listOf("20260101000000_first", "20260102000000_second")
        columnNames("Note") shouldContainExactly listOf("id", "title")
    }

    @Test
    fun `a database that has run everything is up to date`() {
        write("20260101000000_first", "CREATE TABLE \"Note\" (\"id\" integer NOT NULL);")
        connect().use { migrator().apply(it) }

        connect().use { migrator().status(it) }.isUpToDate shouldBe true
    }

    @Test
    fun `editing a migration that has already run is noticed and refused`() {
        write("20260101000000_first", "CREATE TABLE \"Note\" (\"id\" integer NOT NULL);")
        connect().use { migrator().apply(it) }

        write("20260101000000_first", "CREATE TABLE \"Note\" (\"id\" bigint NOT NULL);")

        val status = connect().use { migrator().status(it) }
        status.edited shouldContainExactly listOf("20260101000000_first")
        status.hasDrifted shouldBe true

        val failure = shouldThrow<VolanMigrationException> { connect().use { migrator().apply(it) } }
        failure.message.shouldContain("write a new migration for the change instead")
    }

    @Test
    fun `a migration the database ran but the project no longer has is noticed and refused`() {
        write("20260101000000_first", "CREATE TABLE \"Note\" (\"id\" integer NOT NULL);")
        connect().use { migrator().apply(it) }

        root.resolve("20260101000000_first").resolve("migration.sql").toFile().delete()
        root.resolve("20260101000000_first").toFile().delete()

        connect().use { migrator().status(it) }.missing shouldContainExactly listOf("20260101000000_first")
        shouldThrow<VolanMigrationException> { connect().use { migrator().apply(it) } }
            .message.shouldContain("no longer on disk")
    }

    @Test
    fun `a migration that fails leaves behind neither its changes nor a claim to have made them`() {
        write("20260101000000_first", "CREATE TABLE \"Note\" (\"id\" integer NOT NULL);")
        write("20260102000000_broken", "ALTER TABLE \"Note\" ADD COLUMN \"title\" text;\nTHIS IS NOT SQL;")

        shouldThrow<VolanMigrationException> { connect().use { migrator().apply(it) } }
            .message.shouldContain("Nothing it asked for was applied")

        columnNames("Note") shouldContainExactly listOf("id")
        val status = connect().use { migrator().status(it) }
        status.applied.map { it.id } shouldContainExactly listOf("20260101000000_first")
        status.pending.map { it.id } shouldContainExactly listOf("20260102000000_broken")
    }

    @Test
    fun `a migration can be recorded as applied for a database already in that shape`() {
        connect().use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE \"Note\" (\"id\" integer NOT NULL)") }
        }
        val existing = write("20260101000000_first", "CREATE TABLE \"Note\" (\"id\" integer NOT NULL);")

        connect().use { migrator().markApplied(it, existing) }

        connect().use { migrator().status(it) }.isUpToDate shouldBe true
    }

    @Test
    fun `the reference schema is applied as a migration and recorded as one`() {
        val sql = SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(Fixtures.blog()))
            .toSql(io.github.thirtyeighttwentysix.volan.dialect.postgres.PostgresDialect)
        write("20260101000000_initial", sql)

        connect().use { migrator().apply(it) }

        connect().use { migrator().status(it) }.isUpToDate shouldBe true
        connect().use { migrator().status(it) }.applied.single().appliedSteps shouldBe 22
    }

    private fun migrator() = Migrator(MigrationDirectory(root), MigrationJournal(), clock)

    private fun write(id: String, sql: String): MigrationFile {
        val directory = root.resolve(id)
        directory.createDirectories()
        directory.resolve("migration.sql").writeText(sql)
        return MigrationFile(id, sql)
    }

    private fun connect(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    private fun columnNames(table: String): List<String> = connect().use { connection ->
        connection.createStatement().use { statement ->
            val sql = "select column_name from information_schema.columns where table_name = '$table' order by ordinal_position"
            statement.executeQuery(sql).use { result ->
                val names = ArrayList<String>()
                while (result.next()) names.add(result.getString(1))
                names
            }
        }
    }
}
