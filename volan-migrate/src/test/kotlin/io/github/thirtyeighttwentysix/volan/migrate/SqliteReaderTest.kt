package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefault
import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyAction
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.ir.NativeType
import io.github.thirtyeighttwentysix.volan.ir.ScalarType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class SqliteReaderTest {
    @Test
    fun `unsupported structures are refused rather than silently lost by push`() {
        val definitions = listOf(
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY, x TEXT CHECK(length(x) > 0))",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY, x TEXT COLLATE NOCASE)",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY, x TEXT DEFAULT 'x' NOT NULL)",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY, x VARCHAR(50))",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY) STRICT",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY) WITHOUT ROWID",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY, x TEXT GENERATED ALWAYS AS ('x'))",
            "CREATE VIRTUAL TABLE Item USING fts5(x)",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY); CREATE VIEW example AS SELECT * FROM Item",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY); CREATE TRIGGER example AFTER INSERT ON Item BEGIN SELECT 1; END",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY, x TEXT); CREATE INDEX example ON Item(x) WHERE id > 0",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY, x TEXT); CREATE INDEX example ON Item(lower(x))",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY, x TEXT); CREATE INDEX example ON Item(x DESC)",
            "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY, x TEXT); CREATE INDEX example ON Item(x COLLATE NOCASE)",
            "CREATE TEMP TABLE Item (id INTEGER)",
            "ATTACH DATABASE ':memory:' AS extra",
        )
        definitions.forEach { sql ->
            DriverManager.getConnection("jdbc:sqlite::memory:").use {
                it.createStatement().use { statement -> statement.executeUpdate(sql) }
                shouldThrow<VolanMigrationException> { SqliteReader().read(it) }
            }
        }
    }

    @Test
    fun `quoted identifiers literals comments and conventional constraint names are retained`() {
        val sql = """
            CREATE TABLE "sqlitex" (
              "odd'name" INTEGER NOT NULL,
              "a""b" TEXT DEFAULT 'a''b,(c)',
              -- a comma in a comment is not a delimiter,
              CONSTRAINT "sqlitex_pkey" PRIMARY KEY ("odd'name"),
              UNIQUE ("a""b")
            )
        """.trimIndent()
        DriverManager.getConnection("jdbc:sqlite::memory:").use {
            it.createStatement().use { statement -> statement.execute(sql) }
            val database = SqliteReader().read(it)
            val table = database.tables.single()
            table.name shouldBe "sqlitex"
            table.columns.map { column -> column.name } shouldBe listOf("odd'name", "a\"b")
            table.columns.last().default shouldBe ColumnDefault.Text("a'b,(c)")
            table.uniques.single().name shouldBe "sqlitex_a\"b_key"
            val pulled = Fixtures.schema(SchemaWriter.write(database, io.github.thirtyeighttwentysix.volan.ir.Provider.SQLITE))
            SchemaMapper.map(pulled) shouldBe database
        }
    }

    @Test
    fun `foreign key actions and composite keys have their declared order`() {
        ForeignKeyAction.entries.forEach { action ->
            val table = SqliteDeclarations("Child").read(
                "CREATE TABLE Child (a INTEGER NOT NULL, b INTEGER NOT NULL, " +
                    "PRIMARY KEY (b, a), CONSTRAINT Child_a_b_fkey FOREIGN KEY (a, b) REFERENCES Parent (x, y) " +
                    "ON DELETE ${action.sql} ON UPDATE ${action.sql})",
            )
            table.primaryKey!!.columns shouldBe listOf("b", "a")
            table.foreignKeys.single().columns shouldBe listOf("a", "b")
            table.foreignKeys.single().targetColumns shouldBe listOf("x", "y")
            table.foreignKeys.single().onDelete shouldBe action
            table.foreignKeys.single().onUpdate shouldBe action
        }
        val unnamed = SqliteDeclarations("Child").read(
            "CREATE TABLE Child (a INTEGER, FOREIGN KEY (a) REFERENCES Parent (id))",
        )
        unnamed.foreignKeys.single().name shouldBe "Child_a_fkey"
    }

    @Test
    fun `unique indexes are read accurately but never exported as different constraints`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {
            it.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE Item (id INTEGER NOT NULL PRIMARY KEY, x TEXT); CREATE UNIQUE INDEX example ON Item(x)",
                )
            }
            val database = SqliteReader().read(it)
            database.tables.single().indexes.single().unique shouldBe true
            shouldThrow<VolanMigrationException> {
                SchemaWriter.write(database, io.github.thirtyeighttwentysix.volan.ir.Provider.SQLITE)
            }
        }
    }

    @Test
    fun `malformed or unrepresentable declarations are rejected`() {
        listOf(
            "CREATE TABLE Wrong (id INTEGER)",
            "CREATE TABLE Item ()",
            "CREATE TABLE Item (id INTEGER, CONSTRAINT x CHECK(id > 0))",
            "CREATE TABLE Item (id INTEGER, PRIMARY KEY(id), UNIQUE(id) ON CONFLICT IGNORE)",
            "CREATE TABLE Item (id INTEGER, PRIMARY KEY(id), PRIMARY KEY(id))",
            "CREATE TABLE Item (id INTEGER DEFAULT)",
            "CREATE TABLE Item (id INTEGER, FOREIGN KEY(id) REFERENCES Parent(id) DEFERRABLE)",
            "CREATE TABLE Item (id INTEGER, FOREIGN KEY(id) REFERENCES Parent(id) ON INSERT CASCADE)",
            "CREATE TABLE Item (id INTEGER, FOREIGN KEY(id) REFERENCES Parent(id) ON DELETE SET BAD)",
            "CREATE TABLE Item (id INTEGER, FOREIGN KEY(id) REFERENCES Parent(id) ON DELETE BAD)",
        ).forEach { shouldThrow<VolanMigrationException> { SqliteDeclarations("Item").read(it) } }
        listOf("'unterminated", "/* unfinished", "\"unfinished").forEach {
            shouldThrow<VolanMigrationException> { SqliteSql.tokens(it) }
        }
        shouldThrow<VolanMigrationException> { SqliteSql.groups(SqliteSql.tokens("(a,b")) }
        shouldThrow<VolanMigrationException> { SqliteSql.groups(SqliteSql.tokens("a),b")) }
        SqliteSql.tokens("-- no newline").isEmpty() shouldBe true
        SqliteSql.tokens("[a name] `quoted` /* ignored */").map { it.value } shouldBe listOf("a name", "quoted")
    }

    @Test
    fun `numeric boolean and parenthesized expression defaults are canonical`() {
        fun default(sql: String, type: SqlType) = SqliteDefaults.read(SqliteSql.tokens(sql), ColumnType.Scalar(type))
        default("TRUE", SqlType.BOOLEAN) shouldBe ColumnDefault.Boolean(true)
        default("0", SqlType.BOOLEAN) shouldBe ColumnDefault.Boolean(false)
        default("1", SqlType.BOOLEAN) shouldBe ColumnDefault.Boolean(true)
        default("1e-3", SqlType.DOUBLE) shouldBe ColumnDefault.Number("1e-3")
        default("(length('x'))", SqlType.INTEGER) shouldBe ColumnDefault.Expression("length ( 'x' )")
        SqliteTypes.read("INT", null, null, null) shouldBe ColumnType.Scalar(SqlType.INTEGER)
        SqliteTypes.read("REAL", null, null, null) shouldBe ColumnType.Scalar(SqlType.DOUBLE)
        shouldThrow<VolanMigrationException> { SqliteTypes.canonical(ScalarType.STRING, NativeType("Text", emptyList())) }
    }
}
