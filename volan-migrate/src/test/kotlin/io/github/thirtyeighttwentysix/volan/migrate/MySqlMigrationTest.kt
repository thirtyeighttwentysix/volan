package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer
import io.github.thirtyeighttwentysix.volan.dialect.mysql.MariaDbDialect
import io.github.thirtyeighttwentysix.volan.dialect.mysql.MySqlDialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mysql.MySQLContainer
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MySqlMigrationBase {
    protected abstract val dialect: DdlRenderer
    protected abstract fun container(): JdbcDatabaseContainer<*>
    private val database by lazy { container().apply { start() } }

    @TempDir lateinit var root: Path
    private val directory get() = MigrationDirectory(root.resolve("migrations"))
    private val sync get() = DatabaseSync(MySqlReader(), dialect)
    private val moment = Instant.parse("2026-10-03T00:00:00Z")
    private fun schema(fields: String = "title String? @default(\"hello\")") = Fixtures.schema(
        "datasource db {\n provider = \"${dialect.id}\"\n url = env(\"DATABASE_URL\")\n}\n" +
            "model Item {\n id Int @id @default(autoincrement())\n $fields\n}",
    )

    private fun connect(): Connection = DriverManager.getConnection(database.jdbcUrl, database.username, database.password)

    @BeforeEach fun reset() {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET FOREIGN_KEY_CHECKS=0")
                val names = ArrayList<String>()
                statement.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE()").use { rows ->
                    while (rows.next()) names += rows.getString(1)
                }
                names.forEach { statement.execute("DROP TABLE `${it.replace("`", "``")}`") }
                statement.execute("SET FOREIGN_KEY_CHECKS=1")
            }
        }
    }

    @AfterAll fun stop() {
        database.stop()
    }

    @Test fun `push pull and modifying all column attributes preserve data and defaults`() {
        connect().use { connection ->
            val first = schema()
            sync.push(connection, first)
            connection.createStatement().use { it.execute("INSERT INTO Item () VALUES ()") }
            MySqlReader().read(connection) shouldBe SchemaMapper.map(first)
            SchemaMapper.map(Fixtures.schema(sync.pull(connection))) shouldBe SchemaMapper.map(first)
            val second = schema("title String @default(\"changed\")\nextra String?\n@@index([title])")
            sync.push(connection, second, true)
            sync.plan(connection, second).isEmpty shouldBe true
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT title FROM Item").use {
                    it.next()
                    it.getString(1) shouldBe "hello"
                }
            }
            sync.push(connection, second).isEmpty shouldBe true
            MigrationJournal().exists(connection) shouldBe false
        }
    }

    @Test fun `relations composite keys enums JSON UUID Decimal and fulltext round trip`() {
        val text = "datasource db {\n provider = \"${dialect.id}\"\n url = env(\"DATABASE_URL\")\n}\n" +
            "enum Role {\n MEMBER\n ADMIN @map(\"administrator\")\n}\n" +
            "model Parent {\n a Int\n b Long\n title String @unique\n role Role @default(MEMBER)\n" +
            "children Child[]\n@@id([a,b])\n@@fulltext([title])\n}\n" +
            "model Child {\n id Int @id @default(autoincrement())\n a Int\n b Long\n document Json?\n token Uuid?\n value Decimal?\n" +
            " parent Parent @relation(fields: [a,b], references: [a,b], onDelete: Cascade)\n}\n"
        connect().use { connection ->
            val target = Fixtures.schema(text)
            sync.push(connection, target)
            MySqlReader().read(connection) shouldBe SchemaMapper.map(target)
            SchemaMapper.map(Fixtures.schema(sync.pull(connection))) shouldBe SchemaMapper.map(target)
            val indexed = Fixtures.schema(text.replace(" parent Parent @relation", " @@index([a,b])\n parent Parent @relation"))
            sync.push(connection, indexed)
            sync.plan(connection, indexed).isEmpty shouldBe true
            sync.push(connection, target)
            sync.plan(connection, target).isEmpty shouldBe true
            val without = Fixtures.schema(text.replace("@unique", "").replace("@@fulltext([title])", ""))
            sync.push(connection, without, true)
            sync.plan(connection, without).isEmpty shouldBe true
            val unrelated = Fixtures.schema(
                text.replace("children Child[]\n", "")
                    .replace(" parent Parent @relation(fields: [a,b], references: [a,b], onDelete: Cascade)\n", ""),
            )
            sync.push(connection, unrelated, true)
            sync.plan(connection, unrelated).isEmpty shouldBe true
        }
    }

    @Test fun `versioned migrations retain partial progress checksums and manual recovery`() {
        val first = directory.write(moment, "first", "CREATE TABLE Item (id INT);")
        val broken = directory.write(
            moment.plusSeconds(1),
            "broken",
            "ALTER TABLE Item ADD COLUMN title VARCHAR(191); INSERT INTO missing VALUES(1);",
        )
        val later = directory.write(moment.plusSeconds(2), "later", "INSERT INTO Item VALUES(2,'later');")
        val migrator = Migrator(directory)
        connect().use { connection ->
            MigrationJournal().exists(connection) shouldBe false
            migrator.status(connection).pending.size shouldBe 3
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }
        }
        connect().use { connection ->
            val status = migrator.status(connection)
            status.applied.map { it.id } shouldBe listOf(first.id)
            status.unfinished shouldBe listOf(broken.id)
            MigrationJournal().read(connection).last().appliedSteps shouldBe 1
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }
            shouldThrow<VolanMigrationException> { migrator.markApplied(connection, broken.copy(sql = "SELECT 1;")) }
            connection.createStatement().use {
                it.execute("CREATE TABLE missing(id INT)")
                it.execute("INSERT INTO missing VALUES(1)")
            }
            migrator.markApplied(connection, broken)
            migrator.apply(connection).map { it.id } shouldBe listOf(later.id)
            migrator.apply(connection) shouldBe emptyList()
            root.resolve("migrations/${first.id}/migration.sql").writeText("SELECT 1;")
            migrator.status(connection).edited shouldBe listOf(first.id)
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }
        }
    }

    @Test fun `a failed push blocks retries and can only resolve after repair to its original schema`() {
        connect().use { connection ->
            sync.push(connection, schema())
            connection.createStatement().use { it.execute("INSERT INTO Item (title) VALUES ('hello'),('hello')") }
            val target = schema("title String? @unique @default(\"hello\")\noptional String?")
            shouldThrow<VolanMigrationException> { sync.push(connection, target, true) }
            MigrationJournal(DatabaseSync.PUSH_TABLE).read(connection).count { !it.isFinished } shouldBe 1
            shouldThrow<VolanMigrationException> { sync.push(connection, target, true) }
            shouldThrow<VolanMigrationException> { sync.resolvePush(connection, target) }
            shouldThrow<VolanMigrationException> { Migrator(directory).apply(connection) }
            connection.createStatement().use {
                it.execute("UPDATE Item SET title='repaired' WHERE id=2")
                it.execute("ALTER TABLE Item ADD CONSTRAINT Item_title_key UNIQUE(title)")
            }
            sync.resolvePush(connection, target)
            sync.push(connection, target).isEmpty shouldBe true
        }
    }

    @Test fun `caller transactions and unsupported storage shapes are refused`() {
        connect().use { connection ->
            connection.autoCommit = false
            shouldThrow<VolanMigrationException> { sync.push(connection, schema()) }
            shouldThrow<VolanMigrationException> { Migrator(directory).apply(connection) }
            connection.autoCommit shouldBe false
            connection.rollback()
            connection.autoCommit = true
            connection.createStatement().use {
                it.execute("CREATE TABLE unsupported (id INT, title VARCHAR(20)) ENGINE=InnoDB COLLATE=utf8mb4_bin")
            }
            shouldThrow<VolanMigrationException> { MySqlReader().read(connection) }
        }
    }

    @Test fun `required additions cannot silently fill existing rows with empty values`() {
        connect().use { connection ->
            sync.push(connection, schema())
            connection.createStatement().use { it.execute("INSERT INTO Item () VALUES ()") }
            shouldThrow<VolanMigrationException> {
                sync.push(connection, schema("title String? @default(\"hello\")\nrequired String"), true)
            }
            MigrationJournal(DatabaseSync.PUSH_TABLE).read(connection).all { it.isFinished } shouldBe true
            sync.plan(connection, schema()).isEmpty shouldBe true
        }
    }

    @Test fun `unsupported catalogue definitions are reported rather than discarded`() {
        val definitions = listOf(
            "id INT UNSIGNED PRIMARY KEY",
            "id INT PRIMARY KEY, value TINYINT(2)",
            "id INT PRIMARY KEY, value VARCHAR(20)",
            "id INT PRIMARY KEY, value DECIMAL(10,2)",
            "id INT PRIMARY KEY, value DATETIME(3)",
            "id INT PRIMARY KEY, value TIME(3)",
            "id INT PRIMARY KEY, value VARCHAR(191) COLLATE utf8mb4_general_ci",
            "id INT PRIMARY KEY, value INT GENERATED ALWAYS AS (id+1) STORED",
            "id INT PRIMARY KEY, value INT INVISIBLE",
            "id INT PRIMARY KEY, CHECK (id>0)",
            "id INT PRIMARY KEY, value VARCHAR(191), UNIQUE(value(10))",
        )
        connect().use { connection ->
            definitions.forEach { fields ->
                connection.createStatement().use {
                    it.execute("CREATE TABLE bad ($fields) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin")
                }
                shouldThrow<VolanMigrationException> { MySqlReader().read(connection) }
                connection.createStatement().use { it.execute("DROP TABLE bad") }
            }
            connection.createStatement().use { it.execute("CREATE VIEW bad AS SELECT 1 AS id") }
            shouldThrow<VolanMigrationException> { MySqlReader().read(connection) }
            connection.createStatement().use { it.execute("DROP VIEW bad") }
            connection.createStatement().use { it.execute("CREATE TABLE bad (id INT) ENGINE=MyISAM") }
            shouldThrow<VolanMigrationException> { MySqlReader().read(connection) }
        }
    }

    @Test fun `default strings preserve quotes and backslashes and push cannot pass unfinished migrations`() {
        connect().use { connection ->
            val target = schema(
                "title String @default(\"O'Reilly\\\\books\")\nflag Boolean @default(true)\nnumber Int @default(12)\n" +
                    "amount Decimal @default(1.25)\nline String @default(\"one\\ntwo\")",
            )
            sync.push(connection, target)
            MySqlReader().read(connection) shouldBe SchemaMapper.map(target)
            MigrationJournal().ensure(connection)
            val unfinished = MigrationFile("manual", "SELECT 1;")
            MigrationJournal().begin(connection, unfinished, moment)
            shouldThrow<VolanMigrationException> { sync.push(connection, target) }
        }
    }

    @Test fun `session control is refused before any versioned statement commits`() {
        connect().use { connection ->
            for (command in listOf("SET sql_mode=''", "COMMIT", "LOCK TABLES Item WRITE", "USE other", "BEGIN")) {
                val journal = MigrationJournal()
                journal.ensure(connection)
                val migration = MigrationFile("control", "CREATE TABLE Item(id INT); # comment\n$command;")
                shouldThrow<VolanMigrationException> { CommittedMigration(journal, java.time.Clock.systemUTC()).run(connection, migration) }
                journal.read(connection) shouldBe emptyList()
            }
        }
    }

    @Test fun `named migration locks serialize writers across DDL commits`() {
        directory.write(moment, "first", "CREATE TABLE Item(id INT); INSERT INTO Item VALUES(1);")
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val results = (1..2).map {
                executor.submit(
                    Callable {
                        connect().use { connection ->
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            Migrator(directory).apply(connection).size
                        }
                    },
                )
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            results.sumOf { it.get(30, TimeUnit.SECONDS) } shouldBe 1
        } finally {
            executor.shutdownNow()
        }
    }
}

@EnabledIf("io.github.thirtyeighttwentysix.volan.migrate.Docker#isAvailable")
class MySqlMigrationTest : MySqlMigrationBase() {
    override val dialect: DdlRenderer get() = MySqlDialect
    override fun container(): JdbcDatabaseContainer<*> = MySQLContainer("mysql:8.4")
}

@EnabledIf("io.github.thirtyeighttwentysix.volan.migrate.Docker#isAvailable")
class MariaDbMigrationTest : MySqlMigrationBase() {
    override val dialect: DdlRenderer get() = MariaDbDialect
    override fun container(): JdbcDatabaseContainer<*> = MariaDBContainer("mariadb:11.4")
}
