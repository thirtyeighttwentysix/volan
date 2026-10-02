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
import java.time.Instant

class H2PushTest {
    @TempDir lateinit var root: Path
    private val sync = DatabaseSync(H2Reader(), H2Dialect)
    private fun schema(fields: String = "") = Fixtures.schema(
        "datasource db {\n provider = \"h2\"\n url = env(\"DATABASE_URL\")\n}\nmodel Item {\n id Int @id\n $fields\n}",
    )
    private fun connect(): Connection = DriverManager.getConnection("jdbc:h2:file:${root.resolve("data")}")

    @Test fun `push initializes alters and verifies an H2 database without a versioned journal`() {
        connect().use { connection ->
            sync.push(connection, schema()).isEmpty shouldBe false
            sync.push(connection, schema()).isEmpty shouldBe true
            sync.push(connection, schema("title String? @default(\"hello\")\n@@index([title])")).isEmpty shouldBe false
            val current = schema("title String? @default(\"hello\")\n@@index([title])")
            sync.plan(connection, current).isEmpty shouldBe true
            SchemaMapper.map(Fixtures.schema(sync.pull(connection))) shouldBe SchemaMapper.map(current)
            MigrationJournal().exists(connection) shouldBe false
            MigrationJournal(DatabaseSync.PUSH_TABLE).read(connection).all { it.isFinished } shouldBe true
        }
        connect().use { sync.plan(it, schema("title String? @default(\"hello\")\n@@index([title])")).isEmpty shouldBe true }
    }

    @Test fun `a partial push survives reconnect and requires repair against the original target`() {
        val desired = schema("optional String?\nrequired String")
        connect().use { connection ->
            sync.push(connection, schema())
            connection.createStatement().use { it.execute("INSERT INTO \"Item\" VALUES (1)") }
            shouldThrow<VolanMigrationException> { sync.push(connection, desired) }.message shouldContain "warnings"
            shouldThrow<VolanMigrationException> { sync.push(connection, desired, true) }.message shouldContain "already be committed"
            MigrationJournal(DatabaseSync.PUSH_TABLE).read(connection).last().isFinished shouldBe false
        }
        connect().use { connection ->
            H2Reader().read(connection).table("Item")!!.columns.map { it.name } shouldBe listOf("id", "optional")
            shouldThrow<VolanMigrationException> { sync.push(connection, desired, true) }.message shouldContain "unfinished"
            shouldThrow<VolanMigrationException> { sync.resolvePush(connection, schema()) }.message shouldContain "original target"
            shouldThrow<VolanMigrationException> { sync.resolvePush(connection, desired) }.message shouldContain "Repair"
            val migrator = Migrator(MigrationDirectory(root.resolve("migrations")))
            shouldThrow<VolanMigrationException> { migrator.apply(connection) }.message shouldContain "unfinished database push"
            connection.createStatement().use {
                it.execute("ALTER TABLE \"Item\" ADD COLUMN \"required\" CHARACTER VARYING DEFAULT 'repaired' NOT NULL")
                it.execute("ALTER TABLE \"Item\" ALTER COLUMN \"required\" DROP DEFAULT")
            }
            sync.resolvePush(connection, desired)
            sync.push(connection, desired).isEmpty shouldBe true
            migrator.apply(connection) shouldBe emptyList()
        }
    }

    @Test fun `unfinished versioned migrations block automatic push`() {
        connect().use { connection ->
            val journal = MigrationJournal()
            journal.ensure(connection)
            journal.begin(connection, MigrationFile("unfinished", "SELECT 1;"), Instant.now())
            shouldThrow<VolanMigrationException> { sync.push(connection, schema()) }.message shouldContain "unfinished versioned"
            H2Reader().read(connection) shouldBe DatabaseSchema()
        }
    }

    @Test fun `caller transactions and resolution without unfinished work are refused`() {
        connect().use { connection ->
            shouldThrow<VolanMigrationException> { sync.resolvePush(connection, schema()) }
            connection.autoCommit = false
            shouldThrow<VolanMigrationException> { sync.push(connection, schema()) }
            shouldThrow<VolanMigrationException> { sync.resolvePush(connection, schema()) }
            connection.autoCommit shouldBe false
            connection.rollback()
            MigrationJournal(DatabaseSync.PUSH_TABLE).exists(connection) shouldBe false
        }
    }
}
