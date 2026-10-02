package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefault
import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefinition
import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyAction
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyDefinition
import io.github.thirtyeighttwentysix.volan.dialect.IndexDefinition
import io.github.thirtyeighttwentysix.volan.dialect.PrimaryKeyDefinition
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.dialect.UniqueDefinition
import java.sql.Connection
import java.sql.ResultSet

/** Strict current-database introspection for InnoDB with Volan's canonical MySQL/MariaDB storage. */
public class MySqlReader(private val journalTable: String = MigrationJournal.DEFAULT_TABLE) : DatabaseReader {
    override fun read(connection: Connection): DatabaseSchema {
        if (!connection.isMySql()) throw VolanMigrationException("MySqlReader requires MySQL or MariaDB.")
        val tables = ArrayList<TableDefinition>()
        query(connection, "SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME") { row ->
            val name = row.getString("TABLE_NAME")
            if (name != journalTable && name != DatabaseSync.PUSH_TABLE) {
                if (row.getString("TABLE_TYPE") != "BASE TABLE" ||
                    row.getString("ENGINE") != "InnoDB"
                ) {
                    unsupported("non-InnoDB table or view `$name`")
                }
                if (row.getString("TABLE_COLLATION") != "utf8mb4_bin") unsupported("non-default collation on `$name`")
                tables += table(connection, name)
            }
        }
        query(connection, "SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()") {
            unsupported("trigger `${it.getString(1)}`")
        }
        return DatabaseSchema(tables = tables)
    }

    private fun table(connection: Connection, name: String): TableDefinition {
        val checks = checks(connection, name)
        val columns = columns(connection, name, checks)
        if (checks.isNotEmpty()) unsupported("CHECK constraint on `$name`")
        return keys(connection, name, columns)
    }

    private fun columns(connection: Connection, name: String, checks: MutableList<String>): List<ColumnDefinition> {
        val columns = ArrayList<ColumnDefinition>()
        query(
            connection,
            "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",
            name,
        ) { row ->
            val field = row.getString("COLUMN_NAME")
            val declaration = row.getString("COLUMN_TYPE").lowercase()
            val extra = row.getString("EXTRA").lowercase()
            val unsupportedType = listOf("unsigned", "zerofill").any { it in declaration }
            val unsupportedExtra = listOf("invisible", "virtual generated", "stored generated").any { it in extra }
            if (unsupportedType || unsupportedExtra) {
                unsupported("unsigned, invisible or generated column `$name.$field`")
            }
            val dataType = row.getString("DATA_TYPE").lowercase()
            val jsonCheck = checks.firstOrNull {
                val clause = it.replace(" ", "")
                clause.equals("json_valid(`$field`)", ignoreCase = true) || clause.equals("json_valid(\"$field\")", ignoreCase = true)
            }
            val type = if (dataType == "longtext" && jsonCheck != null) {
                checks.remove(jsonCheck)
                ColumnType.Scalar(SqlType.JSON)
            } else {
                if (dataType == "tinyint" && declaration != "tinyint(1)") unsupported("non-Boolean tinyint `$name.$field`")
                val precision = if (dataType in
                    setOf("datetime", "time")
                ) {
                    row.getInt("DATETIME_PRECISION")
                } else {
                    row.getInt("NUMERIC_PRECISION")
                }
                val length = row.getLong("CHARACTER_MAXIMUM_LENGTH").takeIf { it <= Int.MAX_VALUE }?.toInt()
                MySqlTypes.read(dataType, length, precision, row.getInt("NUMERIC_SCALE"))
            }
            val collation = row.getString("COLLATION_NAME")
            if (collation != null && collation != "utf8mb4_bin") unsupported("column collation `$name.$field`")
            columns +=
                ColumnDefinition(
                    field,
                    type,
                    row.getString("IS_NULLABLE") == "YES",
                    default(row, type, connection),
                    "auto_increment" in extra,
                )
        }
        return columns
    }

    private fun keys(connection: Connection, name: String, columns: List<ColumnDefinition>): TableDefinition {
        val primary = ArrayList<String>()
        val uniques = linkedMapOf<String, MutableList<String>>()
        val foreign = linkedMapOf<String, ForeignDraft>()
        query(
            connection,
            "SELECT k.*, t.CONSTRAINT_TYPE, r.UPDATE_RULE, r.DELETE_RULE FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k " +
                "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t ON t.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND t.TABLE_NAME=k.TABLE_NAME " +
                "AND t.CONSTRAINT_NAME=k.CONSTRAINT_NAME LEFT JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS r " +
                "ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.TABLE_NAME=k.TABLE_NAME AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME " +
                "WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME=? ORDER BY k.CONSTRAINT_NAME,k.ORDINAL_POSITION",
            name,
        ) { row ->
            val constraint = row.getString("CONSTRAINT_NAME")
            val field = row.getString("COLUMN_NAME")
            when (row.getString("CONSTRAINT_TYPE")) {
                "PRIMARY KEY" -> primary += field
                "UNIQUE" -> uniques.getOrPut(constraint) { ArrayList() } += field
                "FOREIGN KEY" -> {
                    if (row.getString("REFERENCED_TABLE_SCHEMA") !=
                        connection.catalog
                    ) {
                        unsupported("cross-database foreign key `$constraint`")
                    }
                    val draft = foreign.getOrPut(constraint) {
                        ForeignDraft(
                            row.getString("REFERENCED_TABLE_NAME"),
                            action(row.getString("DELETE_RULE")),
                            action(row.getString("UPDATE_RULE")),
                        )
                    }
                    draft.columns += field
                    draft.targetColumns += row.getString("REFERENCED_COLUMN_NAME")
                }
            }
        }
        val indexes = indexes(connection, name, uniques.keys)
        return TableDefinition(
            name,
            columns,
            primary.takeIf { it.isNotEmpty() }?.let { PrimaryKeyDefinition("PRIMARY", it) },
            uniques.map { UniqueDefinition(it.key, it.value) },
            indexes,
            foreign.map { (key, value) ->
                ForeignKeyDefinition(key, value.columns, value.target, value.targetColumns, value.delete, value.update)
            },
        )
    }

    private fun indexes(connection: Connection, table: String, uniques: Set<String>): List<IndexDefinition> {
        val indexes = linkedMapOf<String, Pair<Boolean, MutableList<String>>>()
        query(
            connection,
            "SELECT * FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY INDEX_NAME,SEQ_IN_INDEX",
            table,
        ) { row ->
            val name = row.getString("INDEX_NAME")
            val labels = (1..row.metaData.columnCount).map { row.metaData.getColumnLabel(it) }.toSet()
            val invisible = "IS_VISIBLE" in labels && row.getString("IS_VISIBLE") == "NO"
            val ignored = "IGNORED" in labels && row.getString("IGNORED") == "YES"
            if (invisible || ignored) unsupported("invisible or ignored index `$name`")
            if (row.getObject("SUB_PART") != null || row.getString("COLLATION") == "D") {
                unsupported("prefix or descending index `$name`")
            }
            if (name != "PRIMARY" && name !in uniques) {
                val column = row.getString("COLUMN_NAME") ?: unsupported("expression index `$name`")
                val fullText = row.getString("INDEX_TYPE") == "FULLTEXT"
                if (!fullText && row.getString("INDEX_TYPE") != "BTREE") unsupported("index method `$name`")
                if (row.getInt("NON_UNIQUE") == 0) unsupported("unique index `$name` without a readable constraint")
                indexes.getOrPut(name) { fullText to ArrayList() }.second += column
            }
        }
        return indexes.map { (name, value) ->
            IndexDefinition(name, value.second, fullText = value.first)
        }
    }

    private fun checks(connection: Connection, table: String): MutableList<String> {
        val result = ArrayList<String>()
        val maria = connection.metaData.databaseProductName == "MariaDB"
        query(
            connection,
            "SELECT c.CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS c JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t " +
                "ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME " +
                (if (maria) "AND c.TABLE_NAME=t.TABLE_NAME " else "") +
                "WHERE t.TABLE_SCHEMA=DATABASE() AND t.TABLE_NAME=?",
            table,
        ) { result += it.getString(1) }
        return result
    }

    private fun default(row: ResultSet, type: ColumnType, connection: Connection): ColumnDefault? {
        val text = row.getString("COLUMN_DEFAULT") ?: return null
        if (text.equals("NULL", ignoreCase = true) && connection.metaData.databaseProductName == "MariaDB") return null
        val normalized = text.lowercase().replace(" ", "").removeSurrounding("(", ")")
        if (normalized == "current_timestamp(6)") return ColumnDefault.CurrentTimestamp
        if (normalized == "uuid()") return ColumnDefault.GeneratedUuid
        if (type == ColumnType.Scalar(SqlType.BOOLEAN)) return ColumnDefault.Boolean(text == "1")
        if (type in
            listOf(
                ColumnType.Scalar(SqlType.INTEGER),
                ColumnType.Scalar(SqlType.BIGINT),
                ColumnType.Scalar(SqlType.REAL),
                ColumnType.Scalar(SqlType.DOUBLE),
                ColumnType.Scalar(SqlType.NUMERIC),
            )
        ) {
            return ColumnDefault.Number(text.removeSurrounding("'").toBigDecimal().stripTrailingZeros().toPlainString())
        }
        if (text.startsWith("'") && text.endsWith("'")) {
            var literal = text.removeSurrounding("'").replace("''", "'")
            // MariaDB quotes catalogue literals and doubles backslashes even with NO_BACKSLASH_ESCAPES.
            if (connection.metaData.databaseProductName == "MariaDB") {
                literal = Regex("""\\([\\nrtb0Z])""").replace(literal) {
                    when (it.groupValues[1]) {
                        "n" -> "\n"
                        "r" -> "\r"
                        "t" -> "\t"
                        "b" -> "\b"
                        "0" -> "\u0000"
                        "Z" -> "\u001a"
                        else -> "\\"
                    }
                }
            }
            return ColumnDefault.Text(literal)
        }
        if ("DEFAULT_GENERATED" in row.getString("EXTRA")) return ColumnDefault.Expression(text)
        return ColumnDefault.Text(text)
    }

    private fun action(text: String): ForeignKeyAction = when (text) {
        "NO ACTION", "RESTRICT" -> ForeignKeyAction.RESTRICT
        "CASCADE" -> ForeignKeyAction.CASCADE
        "SET NULL" -> ForeignKeyAction.SET_NULL
        else -> unsupported("referential action $text")
    }

    private fun query(connection: Connection, sql: String, table: String? = null, read: (ResultSet) -> Unit) {
        connection.prepareStatement(sql).use { statement ->
            if (table != null) statement.setString(1, table)
            statement.executeQuery().use { rows -> while (rows.next()) read(rows) }
        }
    }

    private fun unsupported(detail: String): Nothing =
        throw VolanMigrationException("MySQL/MariaDB cannot preserve $detail in schema.volan.")

    private class ForeignDraft(
        val target: String,
        val delete: ForeignKeyAction,
        val update: ForeignKeyAction,
    ) {
        val columns = ArrayList<String>()
        val targetColumns = ArrayList<String>()
    }
}
