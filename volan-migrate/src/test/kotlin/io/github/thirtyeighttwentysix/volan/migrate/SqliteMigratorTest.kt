package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.sqlite.SqliteDialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SqliteMigratorTest {
    @TempDir
    lateinit var root: Path
    private val moment = Instant.parse("2026-10-01T12:00:00Z")
    private val directory get() = MigrationDirectory(root.resolve("migrations"))
    private val migrator get() = Migrator(directory, MigrationJournal(), Clock.fixed(moment, ZoneOffset.UTC))

    @Test
    fun `versioned migrations apply once and retain timestamp checksums and step counts`() {
        directory.write(moment, "first", "CREATE TABLE Note (id INTEGER NOT NULL PRIMARY KEY);")
        directory.write(moment.plusSeconds(1), "second", "ALTER TABLE Note ADD COLUMN title TEXT; INSERT INTO Note VALUES (1, 'keep');")
        connect().use {
            migrator.status(it).pending.size shouldBe 2
            migrator.apply(it).size shouldBe 2
            migrator.apply(it).isEmpty() shouldBe true
            val status = migrator.status(it)
            status.isUpToDate shouldBe true
            status.applied.first().startedAt shouldBe moment
            status.applied.last().finishedAt shouldBe moment
            status.applied.last().appliedSteps shouldBe 2
            SqliteReader().read(it).tables.map { table -> table.name } shouldBe listOf("Note")
        }
    }

    @Test
    fun `a failed migration leaves earlier migrations and rolls back its own journal record`() {
        directory.write(moment, "first", "CREATE TABLE Note (id INTEGER NOT NULL PRIMARY KEY);")
        val broken = directory.write(
            moment.plusSeconds(1),
            "broken",
            "ALTER TABLE Note ADD COLUMN title TEXT; INSERT INTO missing VALUES (1);",
        )
        connect().use {
            shouldThrow<VolanMigrationException> { migrator.apply(it) }
            migrator.status(it).applied.size shouldBe 1
            migrator.status(it).pending.single().id shouldBe broken.id
            SqliteReader().read(it).table("Note")!!.columns.size shouldBe 1
            it.autoCommit shouldBe true
        }
    }

    @Test
    fun `edited missing and unfinished history block SQLite migrations`() {
        val first = directory.write(moment, "first", "CREATE TABLE Note (id INTEGER NOT NULL PRIMARY KEY);")
        connect().use {
            migrator.apply(it)
            directoryPath(first).writeText("CREATE TABLE changed (id INTEGER);")
            shouldThrow<VolanMigrationException> { migrator.apply(it) }
            directoryPath(first).writeText(first.sql)
            directoryPath(first).toFile().delete()
            shouldThrow<VolanMigrationException> { migrator.apply(it) }
            directoryPath(first).writeText(first.sql)
            val unfinished = directory.write(moment.plusSeconds(1), "unfinished", "SELECT 1;")
            MigrationJournal().begin(it, unfinished, moment)
            shouldThrow<VolanMigrationException> { migrator.apply(it) }
        }
    }

    @Test
    fun `mark applied records a baseline without executing its SQL`() {
        val baseline = directory.write(moment, "baseline", "CREATE TABLE not_executed (id INTEGER);")
        connect().use {
            migrator.markApplied(it, baseline)
            migrator.status(it).isUpToDate shouldBe true
            SqliteReader().read(it) shouldBe DatabaseSchema()
            migrator.status(it).applied.single().appliedSteps shouldBe 0
            it.autoCommit = false
            shouldThrow<VolanMigrationException> { migrator.apply(it) }
            it.rollback()
        }
    }

    @Test
    fun `a reviewed rebuild script works through the migration journal`() {
        val sync = DatabaseSync(SqliteReader(), SqliteDialect)
        val header = "datasource db {\n provider = \"sqlite\"\n url = env(\"DATABASE_URL\")\n}\n"
        val before = Fixtures.schema(header + "model Note {\n id Int @id\n title String\n}")
        val after = Fixtures.schema(header + "model Note {\n id Int @id\n title String\n extra String?\n}")
        connect().use {
            directory.write(moment, "first", sync.plan(it, before).toSql(SqliteDialect))
            migrator.apply(it)
            it.createStatement().use { sql -> sql.execute("INSERT INTO Note VALUES (1, 'keep')") }
            directory.write(moment.plusSeconds(1), "rebuild", sync.plan(it, after).toSql(SqliteDialect))
            migrator.apply(it).size shouldBe 1
            sync.plan(it, after).isEmpty shouldBe true
            it.createStatement().use { sql ->
                sql.executeQuery("SELECT title FROM Note").use { rows ->
                    rows.next()
                    rows.getString(1) shouldBe "keep"
                }
            }
        }
    }

    @Test
    fun `concurrent migrators serialize and run a migration exactly once`() {
        directory.write(
            moment,
            "first",
            "CREATE TABLE Note (id INTEGER NOT NULL PRIMARY KEY); INSERT INTO Note VALUES (1);",
        )
        parallel { migrator.apply(it).size }.sum() shouldBe 1
        connect().use { migrator.status(it).applied.size shouldBe 1 }
    }

    @Test
    fun `concurrent pushes lock before introspection and avoid stale plans`() {
        val schema = Fixtures.schema(
            "datasource db {\n provider = \"sqlite\"\n url = env(\"DATABASE_URL\")\n}\n model Note {\n id Int @id\n}",
        )
        parallel { DatabaseSync(SqliteReader(), SqliteDialect).push(it, schema).steps.size }.sorted() shouldBe listOf(0, 1)
    }

    @Test
    fun `scripts cannot commit outside the transaction owned by the migrator`() {
        listOf("COMMIT", "END", "ROLLBACK", "BEGIN", "SAVEPOINT x", "RELEASE x").forEachIndexed { index, command ->
            val migrations = MigrationDirectory(root.resolve("control$index"))
            migrations.write(moment, "unsafe", "CREATE TABLE escaped (id INTEGER); /* comment */ $command;")
            DriverManager.getConnection("jdbc:sqlite::memory:").use {
                shouldThrow<VolanMigrationException> { Migrator(migrations).apply(it) }
                SqliteReader().read(it) shouldBe DatabaseSchema()
            }
        }
    }

    private fun parallel(operation: (Connection) -> Int): List<Int> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = List(2) {
                executor.submit(
                    Callable {
                        connect().use { connection ->
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            operation(connection)
                        }
                    },
                )
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return tasks.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun connect() = DriverManager.getConnection("jdbc:sqlite:${root.resolve("db.sqlite")}").also { SqliteDialect.initialize(it) }
    private fun directoryPath(file: MigrationFile): Path =
        root.resolve("migrations").resolve(file.id).createDirectories().resolve("migration.sql")
}
