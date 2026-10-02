package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.h2.H2Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.util.concurrent.TimeoutException
import kotlin.io.path.writeText

class H2MigratorTest {
    @TempDir
    lateinit var root: Path
    private val moment = Instant.parse("2026-10-03T00:00:00Z")
    private val directory get() = MigrationDirectory(root.resolve("migrations"))
    private val migrator get() = Migrator(directory, MigrationJournal(), Clock.fixed(moment, ZoneOffset.UTC))

    @Test
    fun `fresh status is read only and versioned plans apply exactly once`() {
        val schema = Fixtures.schema(
            "datasource db {\n provider = \"h2\"\n url = env(\"DATABASE_URL\")\n}\n" +
                "model Note {\n id Int @id @default(autoincrement())\n title String\n tags String[] @default([])\n}",
        )
        connect().use { connection ->
            migrator.status(connection).isUpToDate shouldBe true
            MigrationJournal().exists(connection) shouldBe false
            val sync = DatabaseSync(H2Reader(), H2Dialect)
            val first = directory.write(moment, "initial", sync.plan(connection, schema).toSql(H2Dialect))
            val second = directory.write(moment.plusSeconds(1), "row", "INSERT INTO \"Note\" (\"title\") VALUES ('keep');")
            migrator.status(connection).pending.map { it.id } shouldBe listOf(first.id, second.id)
            migrator.apply(connection).map { it.id } shouldBe listOf(first.id, second.id)
            migrator.apply(connection) shouldBe emptyList()
            val status = migrator.status(connection)
            status.isUpToDate shouldBe true
            status.applied.last().checksum shouldBe second.checksum
            status.applied.last().startedAt shouldBe moment
            status.applied.last().finishedAt shouldBe moment
            status.applied.last().appliedSteps shouldBe 1
            sync.plan(connection, schema).isEmpty shouldBe true
            scalar(connection, "SELECT COUNT(*) FROM \"Note\"") shouldBe 1
            connection.autoCommit shouldBe true
        }
        connect().use { migrator.status(it).isUpToDate shouldBe true }
    }

    @Test
    fun `failure persists its completed prefix and blocks replay after reopening`() {
        val first = directory.write(moment, "first", "CREATE TABLE Note (id INT PRIMARY KEY);")
        val broken = directory.write(
            moment.plusSeconds(1),
            "broken",
            "ALTER TABLE Note ADD COLUMN title VARCHAR; INSERT INTO Note VALUES (1, 'keep'); INSERT INTO missing VALUES (1);",
        )
        val later = directory.write(moment.plusSeconds(2), "later", "CREATE TABLE later (id INT);")
        connect().use { connection ->
            val failure = shouldThrow<VolanMigrationException> { migrator.apply(connection) }
            failure.message shouldContain "after 2 completed statements"
            failure.message shouldContain "already be committed"
            connection.autoCommit shouldBe true
        }
        connect().use { connection ->
            val status = migrator.status(connection)
            status.applied.map { it.id } shouldBe listOf(first.id)
            status.unfinished shouldBe listOf(broken.id)
            status.pending.map { it.id } shouldBe listOf(later.id)
            val record = MigrationJournal().read(connection).last()
            record.isFinished shouldBe false
            record.appliedSteps shouldBe 2
            record.checksum shouldBe broken.checksum
            scalar(connection, "SELECT COUNT(*) FROM Note WHERE title='keep'") shouldBe 1
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }.message shouldContain "never finished"
            H2Reader().read(connection).tables.map { it.name } shouldBe listOf("NOTE")
        }
    }

    @Test
    fun `manual recovery retains the original checksum and acknowledged progress without replay`() {
        val broken = directory.write(moment, "broken", "CREATE TABLE Note (id INT); INSERT INTO missing VALUES (1);")
        val later = directory.write(moment.plusSeconds(1), "later", "INSERT INTO Note VALUES (2);")
        connect().use { connection ->
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }
            shouldThrow<VolanMigrationException> { migrator.markApplied(connection, broken.copy(sql = "SELECT 1")) }
            MigrationJournal().read(connection).single().isFinished shouldBe false
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE missing (id INT)")
                statement.execute("INSERT INTO missing VALUES (1)")
            }
            migrator.markApplied(connection, broken)
            val record = MigrationJournal().read(connection).single()
            record.appliedSteps shouldBe 1
            record.checksum shouldBe broken.checksum
            record.finishedAt shouldBe moment
            shouldThrow<VolanMigrationException> { migrator.markApplied(connection, broken) }
            migrator.apply(connection).map { it.id } shouldBe listOf(later.id)
            scalar(connection, "SELECT COUNT(*) FROM missing") shouldBe 1
            scalar(connection, "SELECT COUNT(*) FROM Note") shouldBe 1
        }
    }

    @Test
    fun `a failure in the first statement still leaves unfinished history`() {
        val broken = directory.write(moment, "broken", "INSERT INTO missing VALUES (1);")
        connect().use { connection ->
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }
            val record = MigrationJournal().read(connection).single()
            record.appliedSteps shouldBe 0
            record.isFinished shouldBe false
            migrator.status(connection).unfinished shouldBe listOf(broken.id)
        }
    }

    @Test
    fun `edited and missing migrations refuse further application`() {
        val first = directory.write(moment, "first", "CREATE TABLE Note (id INT);")
        connect().use { connection ->
            migrator.apply(connection)
            val path = root.resolve("migrations/${first.id}/migration.sql")
            path.writeText("CREATE TABLE different (id INT);")
            migrator.status(connection).edited shouldBe listOf(first.id)
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }
            path.writeText(first.sql)
            path.toFile().delete()
            migrator.status(connection).missing shouldBe listOf(first.id)
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }
            path.writeText(first.sql)
            migrator.status(connection).isUpToDate shouldBe true
        }
    }

    @Test
    fun `baseline does not execute SQL and a custom current schema journal is hidden by introspection`() {
        val journal = MigrationJournal("history")
        val custom = Migrator(directory, journal)
        val baseline = directory.write(moment, "baseline", "CREATE TABLE absent (id INT);")
        connect().use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA app") }
            connection.schema = "APP"
            custom.markApplied(connection, baseline)
            custom.status(connection).isUpToDate shouldBe true
            journal.read(connection).single().appliedSteps shouldBe 0
            H2Reader("history").read(connection) shouldBe DatabaseSchema()
            connection.schema shouldBe "APP"
            connection.schema = "PUBLIC"
            journal.exists(connection) shouldBe false
        }
    }

    @Test
    fun `apply and recovery refuse a caller transaction without committing it`() {
        val baseline = directory.write(moment, "baseline", "CREATE TABLE absent (id INT);")
        connect().use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE Note (id INT)") }
            connection.autoCommit = false
            connection.createStatement().use { it.execute("INSERT INTO Note VALUES (1)") }
            migrator.status(connection).pending.size shouldBe 1
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }
            shouldThrow<VolanMigrationException> { migrator.markApplied(connection, baseline) }
            MigrationJournal().exists(connection) shouldBe false
            connection.autoCommit shouldBe false
            connection.rollback()
            scalar(connection, "SELECT COUNT(*) FROM Note") shouldBe 0
        }
    }

    @Test
    fun `session and transaction commands are refused before any statement or start record`() {
        val controls = listOf(
            "COMMIT", "ROLLBACK", "BEGIN", "SAVEPOINT x", "RELEASE x", "PREPARE COMMIT x",
            "SET AUTOCOMMIT FALSE", "SET EXCLUSIVE 0", "SET SCHEMA PUBLIC", "USE PUBLIC", "SHUTDOWN",
            "RUNSCRIPT FROM 'file.sql'", "EXECUTE IMMEDIATE 'SET EXCLUSIVE 0'",
        )
        connect().use { connection ->
            controls.forEach { control ->
                val file = directory.write(moment, "control", "CREATE TABLE escaped (id INT); /* leading */ $control;")
                shouldThrow<VolanMigrationException> { migrator.apply(connection) }.message shouldContain "Migrator owns"
                MigrationJournal().read(connection) shouldBe emptyList()
                H2Reader().read(connection) shouldBe DatabaseSchema()
                root.resolve("migrations/${file.id}/migration.sql").toFile().delete()
                connection.autoCommit shouldBe true
            }
        }
    }

    @Test
    fun `malformed SQL is refused before a start record and a corrected file can run`() {
        val first = directory.write(moment, "first", "CREATE TABLE Note (id INT); SELECT 'unterminated;")
        connect().use { connection ->
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }
            MigrationJournal().read(connection) shouldBe emptyList()
            H2Reader().read(connection) shouldBe DatabaseSchema()
            root.resolve("migrations/${first.id}/migration.sql").writeText("CREATE TABLE Note (id INT);")
            migrator.apply(connection).size shouldBe 1
        }
    }

    @Test
    fun `administrator privilege is required before creating the journal`() {
        directory.write(moment, "first", "CREATE TABLE Note (id INT);")
        connect().use { connection ->
            connection.createStatement().use { it.execute("CREATE USER reader PASSWORD 'test'") }
            DriverManager.getConnection(url, "reader", "test").use { reader ->
                shouldThrow<VolanMigrationException> { migrator.apply(reader) }.message shouldContain "administrator"
            }
            MigrationJournal().exists(connection) shouldBe false
        }
    }

    @Test
    fun `concurrent migrators cannot replay a migration across DDL commits`() {
        directory.write(moment, "first", "CREATE TABLE Note (id INT PRIMARY KEY); INSERT INTO Note VALUES (1);")
        connect().use { first ->
            connect().use { second ->
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val results = listOf(first, second).map { connection ->
                        executor.submit(
                            Callable {
                                ready.countDown()
                                check(start.await(10, TimeUnit.SECONDS))
                                runCatching { migrator.apply(connection).size }
                            },
                        )
                    }
                    check(ready.await(10, TimeUnit.SECONDS))
                    start.countDown()
                    val completed = results.map { it.get(20, TimeUnit.SECONDS) }
                    completed.mapNotNull { it.getOrNull() }.sum() shouldBe 1
                    completed.mapNotNull { it.exceptionOrNull() }.forEach { (it is VolanMigrationException) shouldBe true }
                    scalar(first, "SELECT COUNT(*) FROM Note") shouldBe 1
                    migrator.status(second).isUpToDate shouldBe true
                    migrator.apply(second) shouldBe emptyList()
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `exclusive mode is released on exceptional exit and owning session disconnect`() {
        connect().use { first ->
            connect().use { second ->
                shouldThrow<IllegalStateException> {
                    withMigrationLock(first) { throw IllegalStateException("stop") }
                }
                scalar(second, "SELECT 1") shouldBe 1
                first.createStatement().use { it.execute("SET EXCLUSIVE 1") }
                first.close()
                withMigrationLock(second) { scalar(second, "SELECT 1") shouldBe 1 }
            }
        }
    }

    @Test
    fun `exclusive access pauses other connections across multiple implicit DDL commits`() {
        connect().use { first ->
            connect().use { second ->
                val executor = Executors.newSingleThreadExecutor()
                val attempted = CountDownLatch(1)
                try {
                    val blocked = withMigrationLock(first) {
                        first.createStatement().use { it.execute("CREATE TABLE Note (id INT)") }
                        val future = executor.submit(
                            Callable {
                                attempted.countDown()
                                scalar(second, "SELECT 1")
                            },
                        )
                        check(attempted.await(10, TimeUnit.SECONDS))
                        shouldThrow<TimeoutException> { future.get(250, TimeUnit.MILLISECONDS) }
                        first.createStatement().use { it.execute("ALTER TABLE Note ADD COLUMN title VARCHAR") }
                        shouldThrow<TimeoutException> { future.get(250, TimeUnit.MILLISECONDS) }
                        future
                    }
                    blocked.get(10, TimeUnit.SECONDS) shouldBe 1
                    second.isClosed shouldBe false
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `disconnect between DDL and its acknowledgement cannot trigger automatic replay`() {
        val migration = directory.write(moment, "first", "CREATE TABLE Note (id INT);")
        connect().use { connection ->
            val journal = MigrationJournal()
            journal.ensure(connection)
            journal.begin(connection, migration, moment)
            connection.createStatement().use { it.execute(migration.sql) }
            // Simulate losing the session before the durable progress update.
        }
        connect().use { connection ->
            MigrationJournal().read(connection).single().appliedSteps shouldBe 0
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }.message shouldContain "never finished"
            H2Reader().read(connection).tables.map { it.name } shouldBe listOf("NOTE")
            migrator.markApplied(connection, migration)
            migrator.status(connection).isUpToDate shouldBe true
        }
    }

    private val url get() = "jdbc:h2:file:${root.resolve("data")};LOCK_TIMEOUT=5000"
    private fun connect(): Connection = DriverManager.getConnection(url)
    private fun scalar(connection: Connection, sql: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            check(rows.next())
            rows.getInt(1)
        }
    }
}
