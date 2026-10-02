package io.github.thirtyeighttwentysix.volan.dialect.mysql

import io.github.thirtyeighttwentysix.volan.Json
import io.github.thirtyeighttwentysix.volan.dialect.ColumnChange
import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.ConstraintKind
import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer
import io.github.thirtyeighttwentysix.volan.dialect.DdlStatement
import io.github.thirtyeighttwentysix.volan.dialect.DialectCapabilities
import io.github.thirtyeighttwentysix.volan.dialect.IndexDefinition
import io.github.thirtyeighttwentysix.volan.dialect.SqlNulls
import io.github.thirtyeighttwentysix.volan.dialect.SqlOrder
import io.github.thirtyeighttwentysix.volan.dialect.SqlSelect
import io.github.thirtyeighttwentysix.volan.dialect.SqlSelectItem
import io.github.thirtyeighttwentysix.volan.dialect.SqlStatement
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.dialect.VolanDialectException
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** InnoDB storage and SQL common to MySQL 8.4+ and MariaDB 11.4+. */
public open class MySqlRenderer(override val id: String) :
    DdlRenderer(
        DialectCapabilities(false, false, false, false, false, true, false, MAXIMUM_PARAMETERS),
    ) {
    override val timestampWithoutTimeZone: Boolean get() = true
    override val likeEscape: Char get() = '!'
    override val defaultValuesClause: String get() = "() VALUES ()"
    override val currentTimestamp: String get() = "CURRENT_TIMESTAMP(6)"
    override val generatedUuid: String get() = "(UUID())"

    override fun quote(name: String): String = "`" + name.replace("`", "``") + "`"

    override fun initialize(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("SET SESSION time_zone = '+00:00'")
            statement.execute("SET SESSION sql_mode = CONCAT_WS(',', @@sql_mode, 'ANSI_QUOTES,NO_BACKSLASH_ESCAPES,STRICT_TRANS_TABLES')")
        }
    }

    override fun renderForUpdate(select: SqlSelect): SqlStatement = render(select).let { it.copy(sql = it.sql + " FOR UPDATE") }

    override fun render(select: SqlSelect): SqlStatement {
        if (select.distinctOn.isNotEmpty()) {
            val columns = select.items.mapNotNull { (it as? SqlSelectItem.Column)?.expression }.toSet()
            if (select.distinctOn.toSet() !=
                columns
            ) {
                throw VolanDialectException("$id DISTINCT requires selecting exactly the distinct columns.")
            }
        }
        return super.render(select)
    }

    override fun Builder.appendOrderBy(terms: List<SqlOrder>) {
        if (terms.isEmpty()) return
        append(" ORDER BY ")
        terms.forEachIndexed { index, term ->
            if (index > 0) append(", ")
            if (term.nulls != SqlNulls.DEFAULT) {
                append('(')
                appendExpression(term.expression)
                append(if (term.nulls == SqlNulls.LAST) " IS NULL) ASC, " else " IS NULL) DESC, ")
            }
            appendExpression(term.expression)
            append(if (term.descending) " DESC" else " ASC")
        }
    }

    override fun Builder.appendLimit(limit: Int?, offset: Int?) {
        if (limit == null && offset != null) append(" LIMIT 18446744073709551615")
        appendStandardLimit(limit, offset)
    }

    override fun typeName(type: SqlType): String = when (type) {
        SqlType.TEXT -> "VARCHAR(191)"
        SqlType.INTEGER -> "INT"
        SqlType.BIGINT -> "BIGINT"
        SqlType.REAL -> "FLOAT"
        SqlType.DOUBLE -> "DOUBLE"
        SqlType.NUMERIC -> "DECIMAL(65,30)"
        SqlType.BOOLEAN -> "BOOLEAN"
        SqlType.TIMESTAMP -> "DATETIME(6)"
        SqlType.DATE -> "DATE"
        SqlType.TIME -> "TIME(6)"
        SqlType.JSON -> "JSON"
        SqlType.UUID -> "CHAR(36)"
        SqlType.BLOB -> "LONGBLOB"
    }

    override fun render(type: ColumnType): String = when (type) {
        is ColumnType.Array -> throw VolanDialectException("$id does not support scalar arrays.")
        is ColumnType.Native -> throw VolanDialectException("$id @db overrides are not yet supported.")
        else -> super.render(type)
    }

    override fun createTable(create: DdlStatement.CreateTable): String =
        super.createTable(create) + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"

    override fun autoIncrementType(type: ColumnType): String = when (type) {
        ColumnType.Scalar(SqlType.INTEGER), ColumnType.Scalar(SqlType.BIGINT) -> "${render(type)} AUTO_INCREMENT"
        else -> throw VolanDialectException("$id autoincrement requires Int or Long.")
    }

    override fun render(ddl: DdlStatement): List<SqlStatement> = when (ddl) {
        is DdlStatement.CreateConstrainedTable -> render(ddl.definition) +
            ddl.uniques.flatMap { render(DdlStatement.AddUnique(ddl.definition.table, it)) } +
            ddl.foreignKeys.flatMap { render(DdlStatement.AddForeignKey(ddl.definition.table, it)) }
        is DdlStatement.ModifyColumn -> sql("ALTER TABLE ${quote(ddl.table)} MODIFY COLUMN ${column(ddl.definition)}")
        is DdlStatement.DropIndex -> sql("DROP INDEX ${quote(ddl.name)} ON ${quote(ddl.table)}")
        is DdlStatement.DropConstraint -> when (ddl.kind) {
            ConstraintKind.PRIMARY_KEY -> sql("ALTER TABLE ${quote(ddl.table)} DROP PRIMARY KEY")
            ConstraintKind.UNIQUE -> sql("ALTER TABLE ${quote(ddl.table)} DROP INDEX ${quote(ddl.name)}")
            ConstraintKind.FOREIGN_KEY -> sql("ALTER TABLE ${quote(ddl.table)} DROP FOREIGN KEY ${quote(ddl.name)}")
            ConstraintKind.UNKNOWN -> throw VolanDialectException(
                "$id constraint removal requires catalogue context; use DatabaseSync.plan.",
            )
        }
        is DdlStatement.AlterColumn -> alterColumn(ddl)
        else -> super.render(ddl)
    }

    override fun alterColumn(alter: DdlStatement.AlterColumn): List<SqlStatement> = when (alter.change) {
        is ColumnChange.Default -> super.alterColumn(alter)
        else -> throw VolanDialectException("$id column changes require the complete column definition; use DatabaseSync.plan.")
    }

    override fun createIndex(table: String, index: IndexDefinition): String = if (index.fullText) {
        "CREATE FULLTEXT INDEX ${quote(index.name)} ON ${quote(table)} ${columns(index.columns)}"
    } else {
        super.createIndex(table, index)
    }

    override fun jdbcValue(value: Any?): Any? = when (value) {
        is Instant -> LocalDateTime.ofInstant(value, ZoneOffset.UTC)
        is UUID -> value.toString()
        is Json -> value.raw
        else -> value
    }

    // Vendor error numbers, rather than MariaDB's generic 23000 state, identify the constraint.
    @Suppress("MagicNumber")
    override fun sqlState(exception: SQLException): String? = when (exception.errorCode) {
        1062 -> "23505"
        1048, 1364 -> "23502"
        1451, 1452 -> "23503"
        3819, 4025 -> "23514"
        1205, 1213 -> "40001"
        else -> exception.sqlState
    }

    private fun sql(text: String): List<SqlStatement> = listOf(SqlStatement(text, emptyList()))
}

private const val MAXIMUM_PARAMETERS = 65_535

public object MySqlDialect : MySqlRenderer("mysql")

public object MariaDbDialect : MySqlRenderer("mariadb")
