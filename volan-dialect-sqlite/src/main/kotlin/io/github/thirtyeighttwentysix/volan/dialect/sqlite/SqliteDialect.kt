package io.github.thirtyeighttwentysix.volan.dialect.sqlite

import io.github.thirtyeighttwentysix.volan.dialect.DialectCapabilities
import io.github.thirtyeighttwentysix.volan.dialect.SqlCondition
import io.github.thirtyeighttwentysix.volan.dialect.SqlExpression
import io.github.thirtyeighttwentysix.volan.dialect.SqlInsert
import io.github.thirtyeighttwentysix.volan.dialect.SqlRenderer
import io.github.thirtyeighttwentysix.volan.dialect.SqlSelect
import io.github.thirtyeighttwentysix.volan.dialect.SqlSelectItem
import io.github.thirtyeighttwentysix.volan.dialect.SqlTextMatch
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
public object SqliteDialect : SqlRenderer(
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
