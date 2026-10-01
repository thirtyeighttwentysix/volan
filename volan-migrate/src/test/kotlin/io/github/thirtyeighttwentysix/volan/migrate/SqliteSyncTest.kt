package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.DdlStatement
import io.github.thirtyeighttwentysix.volan.dialect.sqlite.SqliteDialect
import io.github.thirtyeighttwentysix.volan.ir.Provider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

class SqliteSyncTest {
    private val sync = DatabaseSync(SqliteReader(), SqliteDialect)

    @Test
    fun `all scalar types relations mapped enums and join tables round trip through SQLite`() = database { connection ->
        val text = requireNotNull(javaClass.getResourceAsStream("/fixtures/sqlite.volan")).bufferedReader().use { it.readText() }
        val wanted = Fixtures.schema(text)
        sync.push(connection, wanted).isEmpty shouldBe false
        SqliteReader().read(connection) shouldBe SchemaMapper.map(wanted)
        val pulled = Fixtures.schema(sync.pull(connection))
        pulled.datasource.provider shouldBe Provider.SQLITE
        SchemaMapper.map(pulled) shouldBe SchemaMapper.map(wanted)
        sync.push(connection, pulled).isEmpty shouldBe true
        sync.drift(connection, SchemaMapper.map(wanted)).isEmpty shouldBe true
        MigrationJournal().exists(connection) shouldBe false
    }

    @Test
    fun `database defaults use the runtime timestamp and UUID conventions`() = database { connection ->
        val schema = Fixtures.schema(
            header + """
            model Defaults {
              id Int @id @default(autoincrement())
              moment DateTime @default(now())
              token Uuid @default(uuid())
              title String @default("it's (a,b); DEFAULT")
              truth Boolean @default(false)
              number Double @default(-1.25)
              expression String @default(dbgenerated("upper('hello')"))
            }
            """.trimIndent(),
        )
        sync.push(connection, schema)
        sql(connection, "INSERT INTO Defaults DEFAULT VALUES")
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM Defaults").use {
                it.next()
                Instant.parse(it.getString("moment"))
                UUID.fromString(it.getString("token")).version() shouldBe 4
                it.getString("title") shouldBe "it's (a,b); DEFAULT"
                it.getInt("truth") shouldBe 0
                it.getDouble("number") shouldBe -1.25
                it.getString("expression") shouldBe "HELLO"
            }
        }
        sync.push(connection, schema).isEmpty shouldBe true
        SchemaMapper.map(Fixtures.schema(sync.pull(connection))) shouldBe SchemaMapper.map(schema)
    }

    @Test
    fun `rebuild fills new columns with defaults and retains existing values`() = database { connection ->
        val before = note("title String")
        sync.push(connection, before)
        sql(connection, "INSERT INTO Note (title) VALUES ('keep')")
        val after = note("title String\n enabled Boolean @default(true)\n optional String?")
        val plan = sync.plan(connection, after)
        (plan.steps.single().statement is DdlStatement.RebuildTable) shouldBe true
        sync.push(connection, after)
        text(connection, "SELECT title FROM Note") shouldBe "keep"
        number(connection, "SELECT enabled FROM Note") shouldBe 1
        sync.push(connection, after).isEmpty shouldBe true
    }

    @Test
    fun `changing a default preserves old values and supplies the new default to future rows`() = database { connection ->
        sync.push(connection, note("title String @default(\"old\")"))
        sql(connection, "INSERT INTO Note DEFAULT VALUES")
        val after = note("title String @default(\"new\")")
        sync.push(connection, after)
        sql(connection, "INSERT INTO Note DEFAULT VALUES")
        text(connection, "SELECT group_concat(title, ',') FROM Note ORDER BY id") shouldBe "old,new"
    }

    @Test
    fun `rebuilding a parent does not cascade-delete its children`() = database { connection ->
        val before = Fixtures.schema(relations)
        sync.push(connection, before)
        sql(connection, "INSERT INTO Parent DEFAULT VALUES")
        sql(connection, "INSERT INTO Child (parentId) VALUES (1)")
        val after = Fixtures.schema(relations.replace("children Child[]", "label String?\n children Child[]"))
        sync.push(connection, after)
        number(connection, "SELECT count(*) FROM Child") shouldBe 1
        number(connection, "PRAGMA foreign_keys") shouldBe 1
        sync.push(connection, after).isEmpty shouldBe true
    }

    @Test
    fun `autoincrement remembers deleted high IDs through a populated or empty rebuild`() {
        listOf(false, true).forEach { empty ->
            database { connection ->
                sync.push(connection, note("title String?"))
                sql(connection, "INSERT INTO Note (id) VALUES (100)")
                if (empty) sql(connection, "DELETE FROM Note")
                sync.push(connection, note("title String?\n extra String?"))
                sql(connection, "INSERT INTO Note DEFAULT VALUES")
                number(connection, "SELECT max(id) FROM Note") shouldBe 101
            }
        }
    }

    @Test
    fun `dropping a column needs review and retains every surviving row`() = database { connection ->
        sync.push(connection, note("title String"))
        sql(connection, "INSERT INTO Note (title) VALUES ('gone')")
        val after = note("")
        shouldThrow<VolanMigrationException> { sync.push(connection, after) }
        sync.push(connection, after, acceptWarnings = true)
        number(connection, "SELECT count(*) FROM Note") shouldBe 1
        sync.plan(connection, after).isEmpty shouldBe true
    }

    @Test
    fun `unique and not-null violations roll back the complete rebuild`() {
        listOf(
            "title String?" to "title String",
            "title String" to "title String @unique",
            "title String" to "title String\n required String",
        ).forEach { (before, after) ->
            database { connection ->
                sync.push(connection, note(before))
                val value = if (before.endsWith("?")) "NULL" else "'duplicate'"
                sql(connection, "INSERT INTO Note (title) VALUES ($value), ($value)")
                shouldThrow<VolanMigrationException> { sync.push(connection, note(after)) }
                shouldThrow<VolanMigrationException> { sync.push(connection, note(after), acceptWarnings = true) }
                number(connection, "SELECT count(*) FROM Note") shouldBe 2
                connection.autoCommit shouldBe true
                number(connection, "PRAGMA foreign_keys") shouldBe 1
                sync.plan(connection, note(before)).isEmpty shouldBe true
                number(connection, "SELECT count(*) FROM sqlite_schema WHERE name LIKE '__volan_rebuild_%'") shouldBe 0
            }
        }
    }

    @Test
    fun `foreign key checks roll back unrelated changes when existing data is invalid`() = database { connection ->
        sync.push(connection, Fixtures.schema(relations))
        sql(connection, "PRAGMA foreign_keys = OFF")
        sql(connection, "INSERT INTO Child (parentId) VALUES (99)")
        sql(connection, "PRAGMA foreign_keys = ON")
        val after = Fixtures.schema(relations.replace("children Child[]", "label String?\n children Child[]"))
        shouldThrow<VolanMigrationException> { sync.push(connection, after) }
        sync.plan(connection, Fixtures.schema(relations)).isEmpty shouldBe true
        number(connection, "SELECT parentId FROM Child") shouldBe 99
        number(connection, "PRAGMA foreign_keys") shouldBe 1
    }

    @Test
    fun `index-only changes avoid table rebuilds and recreate indexes after a column change`() = database { connection ->
        val plain = note("title String")
        val indexed = note("title String\n @@index([title])")
        sync.push(connection, plain)
        sql(connection, "INSERT INTO Note (title) VALUES ('keep')")
        (sync.plan(connection, indexed).steps.single().statement is DdlStatement.CreateIndex) shouldBe true
        sync.push(connection, indexed)
        val extended = note("title String\n extra String?\n @@index([title])")
        sync.push(connection, extended)
        SqliteReader().read(connection).table("Note")!!.indexes.size shouldBe 1
        sync.plan(connection, plain).steps.size shouldBe 1
        sync.push(connection, plain, acceptWarnings = true)
        text(connection, "SELECT title FROM Note") shouldBe "keep"
    }

    @Test
    fun `a rebuild can retain the number of rows without any surviving columns`() = database { connection ->
        sql(connection, "CREATE TABLE Note (old TEXT NOT NULL)")
        sql(connection, "INSERT INTO Note VALUES ('a'), ('b')")
        val target = note("title String @default(\"new\")")
        sync.push(connection, target, acceptWarnings = true)
        number(connection, "SELECT count(*) FROM Note") shouldBe 2
        text(connection, "SELECT title FROM Note LIMIT 1") shouldBe "new"
    }

    @Test
    fun `dropping every table keeps the migration journal`() = database { connection ->
        sync.push(connection, Fixtures.schema(relations))
        MigrationJournal().ensure(connection)
        sync.push(connection, Fixtures.schema(header), acceptWarnings = true)
        SqliteReader().read(connection) shouldBe DatabaseSchema()
        MigrationJournal().exists(connection) shouldBe true
    }

    @Test
    fun `caller transactions and mismatched schema providers are refused without writes`() = database { connection ->
        connection.autoCommit = false
        shouldThrow<VolanMigrationException> { sync.push(connection, note("")) }
        connection.rollback()
        connection.autoCommit = true
        shouldThrow<VolanMigrationException> { sync.plan(connection, Fixtures.blog()) }
        SqliteReader().read(connection) shouldBe DatabaseSchema()
    }

    @Test
    fun `foreign key settings and failed verification are restored`() = database { connection ->
        sql(connection, "PRAGMA foreign_keys = OFF")
        val failing = object : DatabaseReader {
            var reads = 0
            override fun read(connection: Connection): DatabaseSchema {
                check(++reads == 1) { "Injected verification failure" }
                return SqliteReader().read(connection)
            }
        }
        shouldThrow<IllegalStateException> { DatabaseSync(failing, SqliteDialect).push(connection, note("")) }
        SqliteReader().read(connection) shouldBe DatabaseSchema()
        number(connection, "PRAGMA foreign_keys") shouldBe 0
    }

    @Test
    fun `a rebuild refuses a name occupied by a real table`() = database { connection ->
        sync.push(connection, note("title String"))
        sql(connection, "CREATE TABLE __volan_rebuild_Note (id INTEGER NOT NULL)")
        shouldThrow<VolanMigrationException> { sync.plan(connection, note("title String\n extra String?")) }
    }

    private fun note(fields: String) = Fixtures.schema(header + "model Note {\n id Long @id @default(autoincrement())\n $fields\n}")

    private fun database(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {
            SqliteDialect.initialize(it)
            block(it)
        }
    }

    private fun sql(connection: Connection, text: String) {
        connection.createStatement().use { it.execute(text) }
    }

    private fun number(connection: Connection, text: String): Int = connection.createStatement().use {
        it.executeQuery(text).use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    private fun text(connection: Connection, text: String): String = connection.createStatement().use {
        it.executeQuery(text).use { rows ->
            rows.next()
            rows.getString(1)
        }
    }

    private val header = "datasource db {\n provider = \"sqlite\"\n url = env(\"DATABASE_URL\")\n}\n"
    private val relations = header + """
        model Parent {
          id Int @id @default(autoincrement())
          children Child[]
        }
        model Child {
          id Int @id @default(autoincrement())
          parentId Int
          parent Parent @relation(fields: [parentId], references: [id], onDelete: Cascade)
        }
    """.trimIndent()
}
