package io.github.thirtyeighttwentysix.volan.dialect.sqlite

import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefault
import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer
import io.github.thirtyeighttwentysix.volan.dialect.DdlStatement
import io.github.thirtyeighttwentysix.volan.dialect.DialectCapabilities
import io.github.thirtyeighttwentysix.volan.dialect.SqlCondition
import io.github.thirtyeighttwentysix.volan.dialect.SqlExpression
import io.github.thirtyeighttwentysix.volan.dialect.SqlInsert
import io.github.thirtyeighttwentysix.volan.dialect.SqlRenderer
import io.github.thirtyeighttwentysix.volan.dialect.SqlSelect
import io.github.thirtyeighttwentysix.volan.dialect.SqlSelectItem
import io.github.thirtyeighttwentysix.volan.dialect.SqlStatement
import io.github.thirtyeighttwentysix.volan.dialect.SqlTextMatch
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.dialect.VolanDialectException
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

/** SQLite 3.35+; file and in-memory databases share the same generated client API. */
public object SqliteDialect : DdlRenderer(
    DialectCapabilities(
        returningClause = true,
        insertOnConflict = true,
        caseInsensitiveLike = false,
        distinctOn = false,
        nullsOrdering = true,
        tupleComparison = true,
        arrayColumns = false,
        maximumParameters = 999,
    ),
) {
    override val id: String get() = "sqlite"

    override val currentTimestamp: String get() = "strftime('%Y-%m-%dT%H:%M:%f000000Z', 'now')"

    override val generatedUuid: String get() =
        "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || " +
            "substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6)))"

    override fun typeName(type: SqlType): String = when (type) {
        SqlType.TEXT -> "TEXT"
        SqlType.INTEGER -> "INTEGER"
        SqlType.BIGINT -> "BIGINT"
        SqlType.REAL -> "FLOAT"
        SqlType.DOUBLE -> "DOUBLE"
        SqlType.BOOLEAN -> "BOOLEAN"
        SqlType.TIMESTAMP -> "TIMESTAMP TEXT"
        SqlType.DATE -> "DATE TEXT"
        SqlType.TIME -> "TIME TEXT"
        SqlType.JSON -> "JSON TEXT"
        SqlType.UUID -> "UUID TEXT"
        SqlType.BLOB -> "BLOB"
        SqlType.NUMERIC -> throw VolanDialectException("SQLite cannot preserve Decimal arithmetic.")
    }

    override fun render(type: ColumnType): String = when (type) {
        is ColumnType.Array, is ColumnType.Native ->
            throw VolanDialectException("SQLite migrations do not support array or native column types.")
        else -> super.render(type)
    }

    override fun render(default: ColumnDefault): String = when (default) {
        ColumnDefault.CurrentTimestamp, ColumnDefault.GeneratedUuid, is ColumnDefault.Expression -> "(${super.render(default)})"
        ColumnDefault.EmptyArray -> throw VolanDialectException("SQLite cannot store scalar arrays.")
        else -> super.render(default)
    }

    override fun createTable(create: DdlStatement.CreateTable): String {
        val automatic = create.columns.filter { it.autoIncrement }
        if (automatic.isEmpty()) return super.createTable(create)
        val column = automatic.singleOrNull()
            ?: throw VolanDialectException("SQLite supports only one autoincrement column per table.")
        val requiredKey = create.primaryKey?.columns == listOf(column.name) && !column.nullable
        val integral = column.type in listOf(ColumnType.Scalar(SqlType.INTEGER), ColumnType.Scalar(SqlType.BIGINT))
        if (!requiredKey || column.default != null || !integral) {
            throw VolanDialectException("SQLite autoincrement requires a required, single-column integer primary key without a default.")
        }
        // INTEGER is mandatory for a rowid alias; retain Long's client type in the stored declaration.
        val marker = if (column.type == ColumnType.Scalar(SqlType.BIGINT)) " /* volan:Long */" else ""
        val name = create.primaryKey?.name?.let { "CONSTRAINT ${quote(it)} " }.orEmpty()
        val parts = create.columns.map {
            if (it == column) "${quote(it.name)} INTEGER$marker NOT NULL ${name}PRIMARY KEY AUTOINCREMENT" else column(it)
        }
        return "CREATE TABLE ${quote(create.table)} (\n  " + parts.joinToString(",\n  ") + "\n)"
    }

    override fun render(ddl: DdlStatement): List<SqlStatement> = when (ddl) {
        is DdlStatement.RebuildTable -> rebuild(ddl).map { SqlStatement(it, emptyList()) }
        is DdlStatement.AlterColumn, is DdlStatement.AddPrimaryKey, is DdlStatement.AddUnique,
        is DdlStatement.AddForeignKey, is DdlStatement.DropConstraint,
        ->
            throw VolanDialectException("SQLite needs a complete table rebuild for this change; use DatabaseSync.plan.")
        else -> super.render(ddl)
    }

    private fun rebuild(rebuild: DdlStatement.RebuildTable): List<String> {
        val original = rebuild.definition.definition
        val temporary = "__volan_rebuild_${original.table}"
        val target = original.copy(table = temporary)
        val retained = rebuild.copiedColumns
        require(retained.distinct().size == retained.size && retained.all { name -> original.columns.any { it.name == name } }) {
            "A rebuild must copy each surviving column exactly once."
        }
        val copy = if (retained.isNotEmpty()) {
            "INSERT INTO ${quote(temporary)} ${columns(retained)} SELECT " +
                retained.joinToString(", ") { quote(it) } + " FROM ${quote(original.table)}"
        } else {
            val first = original.columns.first()
            val default = first.default?.let { render(it) } ?: "NULL"
            "INSERT INTO ${quote(temporary)} (${quote(first.name)}) SELECT $default FROM ${quote(original.table)}"
        }
        val sequence = if (original.columns.none { it.autoIncrement }) {
            emptyList()
        } else {
            listOf(
                "UPDATE sqlite_sequence SET seq = MAX(seq, COALESCE((SELECT seq FROM sqlite_sequence WHERE name = " +
                    "${literal(original.table)}), seq)) WHERE name = ${literal(temporary)}",
                "INSERT INTO sqlite_sequence (name, seq) SELECT ${literal(temporary)}, seq FROM sqlite_sequence WHERE name = " +
                    "${literal(original.table)} AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = ${literal(temporary)})",
            )
        }
        return listOf(createConstrainedTable(rebuild.definition.copy(definition = target)), copy) + sequence +
            listOf("DROP TABLE ${quote(original.table)}", "ALTER TABLE ${quote(temporary)} RENAME TO ${quote(original.table)}") +
            rebuild.indexes.map { createIndex(original.table, it) }
    }

    override fun createIndex(table: String, index: io.github.thirtyeighttwentysix.volan.dialect.IndexDefinition): String {
        if (index.fullText) throw VolanDialectException("SQLite full-text indexes require explicitly managed FTS tables.")
        return super.createIndex(table, index)
    }

    private val instantFormat = DateTimeFormatterBuilder().appendInstant(9).toFormatter()

    override fun initialize(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.executeQuery("PRAGMA foreign_keys").use { result ->
                if (!result.next() || result.getInt(1) != 1) {
                    throw SQLException("SQLite foreign keys must be enabled before starting a transaction")
                }
            }
        }
    }

    /** One connection preserves private in-memory databases and serializes this pool's writers. */
    override fun poolSize(jdbcUrl: String, requested: Int): Int {
        require(requested > 0) { "a connection pool needs at least one connection" }
        return 1
    }

    // Retiring the sole connection would destroy a private memory or temporary database.
    override fun connectionMaxLifetime(jdbcUrl: String): Long = 0L

    override fun jdbcValue(value: Any?): Any? = when (value) {
        is Instant -> instantFormat.format(value)
        is LocalDate, is LocalTime, is UUID -> value.toString()
        else -> value
    }

    override fun sqlState(exception: SQLException): String? = when ((exception as? SQLiteException)?.resultCode) {
        SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE, SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY -> "23505"
        SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY -> "23503"
        SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL -> "23502"
        SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK -> "23514"
        SQLiteErrorCode.SQLITE_BUSY, SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT, SQLiteErrorCode.SQLITE_LOCKED -> "40001"
        else -> exception.sqlState
    }

    override fun Builder.appendLimit(limit: Int?, offset: Int?) {
        if (limit == null && offset != null) append(" LIMIT -1")
        appendStandardLimit(limit, offset)
    }

    override fun Builder.appendInsert(insert: SqlInsert) {
        if (insert.rows.flatten().any { it is SqlExpression.Keyword && it.text == "DEFAULT" }) {
            throw VolanDialectException("SQLite cannot put DEFAULT in a VALUES row; group inserts by the columns they supply.")
        }
        appendStandardInsert(insert)
    }

    override fun Builder.appendDistinct(select: SqlSelect) {
        val selected = select.items.mapNotNull { (it as? SqlSelectItem.Column)?.expression }
        if (selected.toSet() != select.distinctOn.toSet() || selected.size != select.items.size) {
            throw VolanDialectException(
                "SQLite has no DISTINCT ON; select exactly the distinct fields with projectMany, or use groupBy.",
            )
        }
        appendStandardDistinct(select)
    }

    override fun Builder.appendTextMatch(condition: SqlCondition.TextMatch) {
        if (condition.caseInsensitive) {
            appendStandardTextMatch(condition)
            return
        }
        // GLOB stays case-sensitive even when another application changes SQLite's LIKE pragma.
        val escaped = condition.value.map { character ->
            when (character) {
                '*' -> "[*]"
                '?' -> "[?]"
                '[' -> "[[]"
                else -> character.toString()
            }
        }.joinToString("")
        val glob = when (condition.match) {
            SqlTextMatch.CONTAINS -> "*$escaped*"
            SqlTextMatch.STARTS_WITH -> "$escaped*"
            SqlTextMatch.ENDS_WITH -> "*$escaped"
        }
        appendExpression(condition.column)
        append(" GLOB ")
        bind(glob)
    }
}
