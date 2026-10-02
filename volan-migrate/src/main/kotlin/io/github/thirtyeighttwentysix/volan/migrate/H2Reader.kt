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

/** Reads ordinary tables in the current H2 2.x schema, refusing definitions Volan cannot preserve. */
public class H2Reader(private val journalTable: String = MigrationJournal.DEFAULT_TABLE) : DatabaseReader {
    override fun read(connection: Connection): DatabaseSchema {
        if (connection.metaData.databaseProductName != "H2") throw VolanMigrationException("H2Reader requires an H2 connection.")
        rejectObjects(connection)
        val tables = ArrayList<TableDefinition>()
        query(connection, "SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = CURRENT_SCHEMA() ORDER BY TABLE_NAME") { row ->
            val name = row.getString("TABLE_NAME")
            if (name != journalTable) {
                if (row.getString("TABLE_TYPE") != "BASE TABLE" || row.getString("STORAGE_TYPE") !in setOf("CACHED", "MEMORY")) {
                    unsupported("view, temporary, linked or external table `$name`")
                }
                tables += readTable(connection, name)
            }
        }
        return DatabaseSchema(tables = tables)
    }

    private fun rejectObjects(connection: Connection) {
        query(connection, "SELECT SETTING_NAME, SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS") { row ->
            val value = row.getString("SETTING_VALUE")
            when (row.getString("SETTING_NAME")) {
                "MODE" -> if (value != "REGULAR") unsupported("compatibility mode `$value`")
                "COLLATION" -> if (value != "OFF") unsupported("database collation `$value`")
                "DEFAULT_NULL_ORDERING" -> if (value != "LOW") unsupported("default null ordering `$value`")
                "IGNORECASE" -> if (value != "false") unsupported("case-insensitive text storage")
            }
        }
        for ((catalogue, schema) in listOf(
            "TRIGGERS" to "TRIGGER_SCHEMA",
            "SEQUENCES" to "SEQUENCE_SCHEMA",
            "SYNONYMS" to "SYNONYM_SCHEMA",
        )) {
            query(connection, "SELECT * FROM INFORMATION_SCHEMA.$catalogue WHERE $schema = CURRENT_SCHEMA()") {
                unsupported("${catalogue.lowercase()} in the current schema")
            }
        }
    }

    private fun readTable(connection: Connection, table: String): TableDefinition {
        val columns = ArrayList<ColumnDefinition>()
        query(
            connection,
            "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = CURRENT_SCHEMA() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
            table,
        ) {
            columns += column(connection, table, it)
        }
        if (columns.isEmpty()) unsupported("table `$table` without columns")
        var primary: PrimaryKeyDefinition? = null
        val uniques = ArrayList<UniqueDefinition>()
        val foreignKeys = ArrayList<ForeignKeyDefinition>()
        query(
            connection,
            "SELECT * FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                "WHERE TABLE_SCHEMA = CURRENT_SCHEMA() AND TABLE_NAME = ? ORDER BY CONSTRAINT_NAME",
            table,
        ) { row ->
            val name = row.getString("CONSTRAINT_NAME")
            if (row.getString("ENFORCED") != "YES") unsupported("unenforced constraint `$name`")
            val keys = keyColumns(connection, name)
            when (row.getString("CONSTRAINT_TYPE")) {
                "PRIMARY KEY" -> primary = PrimaryKeyDefinition(name, keys.map { it.column })
                "UNIQUE" -> {
                    if (row.getString("NULLS_DISTINCT") != "YES") unsupported("non-default null semantics on `$name`")
                    uniques += UniqueDefinition(name, keys.map { it.column })
                }
                "FOREIGN KEY" -> foreignKeys += foreignKey(connection, name, keys)
                else -> unsupported("constraint `$name` of type ${row.getString("CONSTRAINT_TYPE")}")
            }
        }
        return TableDefinition(table, columns, primary, uniques, indexes(connection, table), foreignKeys)
    }

    private fun column(connection: Connection, table: String, row: ResultSet): ColumnDefinition {
        val name = row.getString("COLUMN_NAME")
        val customExpression = row.getString("DOMAIN_NAME") != null || row.getString("IS_GENERATED") != "NEVER" ||
            row.getString("COLUMN_ON_UPDATE") != null
        val customVisibilityOrDefault = !row.getBoolean("IS_VISIBLE") || row.getBoolean("DEFAULT_ON_NULL")
        if (customExpression || customVisibilityOrDefault) {
            unsupported("domain, generated, invisible or custom update/default behavior on `$table.$name`")
        }
        val type = if (row.getString("DATA_TYPE") == "ARRAY") {
            if (row.getInt("MAXIMUM_CARDINALITY") != MAX_ARRAY) unsupported("bounded array `$table.$name`")
            var element: ColumnType? = null
            query(
                connection,
                "SELECT * FROM INFORMATION_SCHEMA.ELEMENT_TYPES " +
                    "WHERE OBJECT_SCHEMA = CURRENT_SCHEMA() AND OBJECT_TYPE = 'TABLE' " +
                    "AND OBJECT_NAME = ? AND COLLECTION_TYPE_IDENTIFIER = ?",
                table,
                row.getString("DTD_IDENTIFIER"),
            ) {
                element = readType(it)
            }
            ColumnType.Array(element ?: throw VolanMigrationException("H2 array `$table.$name` has no element metadata."))
        } else {
            readType(row)
        }
        val identity = row.getString("IS_IDENTITY") == "YES"
        if (identity) checkIdentity(row, table, type)
        return ColumnDefinition(name, type, row.getString("IS_NULLABLE") == "YES", default(row.getString("COLUMN_DEFAULT"), type), identity)
    }

    private fun readType(row: ResultSet): ColumnType = H2Types.read(
        row.getString("DATA_TYPE"),
        row.getInt("CHARACTER_MAXIMUM_LENGTH").takeUnless { row.wasNull() },
        row.getInt("NUMERIC_PRECISION").takeUnless { row.wasNull() }
            ?: row.getInt("DATETIME_PRECISION").takeUnless { row.wasNull() },
        row.getInt("NUMERIC_SCALE").takeUnless { row.wasNull() },
    )

    private fun checkIdentity(row: ResultSet, table: String, type: ColumnType) {
        val maximum = when (type) {
            ColumnType.Scalar(SqlType.INTEGER) -> Int.MAX_VALUE.toLong()
            ColumnType.Scalar(SqlType.BIGINT) -> Long.MAX_VALUE
            else -> unsupported("identity type on `$table.${row.getString("COLUMN_NAME")}`")
        }
        val customRange = row.getLong("IDENTITY_START") != 1L || row.getLong("IDENTITY_MINIMUM") != 1L ||
            row.getLong("IDENTITY_MAXIMUM") != maximum
        val customSequence = row.getLong("IDENTITY_INCREMENT") != 1L || row.getString("IDENTITY_CYCLE") != "NO" ||
            row.getLong("IDENTITY_CACHE") != DEFAULT_IDENTITY_CACHE
        if (row.getString("IDENTITY_GENERATION") != "BY DEFAULT" || customRange || customSequence) {
            unsupported("custom identity options on `$table.${row.getString("COLUMN_NAME")}`")
        }
    }

    private fun default(sql: String?, type: ColumnType): ColumnDefault? {
        if (sql == null) return null
        val text = TEXT_LITERAL.matchEntire(sql)
        if (text != null) return ColumnDefault.Text(text.groupValues[1].replace("''", "'"))
        return when {
            sql == "CURRENT_TIMESTAMP(9)" -> ColumnDefault.CurrentTimestamp
            sql == "RANDOM_UUID()" -> ColumnDefault.GeneratedUuid
            sql == "ARRAY []" && type is ColumnType.Array -> ColumnDefault.EmptyArray
            sql == "TRUE" || sql == "FALSE" -> ColumnDefault.Boolean(sql == "TRUE")
            sql.toBigDecimalOrNull() != null -> ColumnDefault.Number(sql)
            else -> ColumnDefault.Expression(sql)
        }
    }

    private fun keyColumns(connection: Connection, name: String): List<KeyColumn> = buildList {
        query(
            connection,
            "SELECT * FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE " +
                "WHERE CONSTRAINT_SCHEMA = CURRENT_SCHEMA() AND CONSTRAINT_NAME = ? ORDER BY ORDINAL_POSITION",
            name,
        ) {
            add(KeyColumn(it.getString("TABLE_NAME"), it.getString("COLUMN_NAME"), it.getInt("POSITION_IN_UNIQUE_CONSTRAINT")))
        }
    }

    private fun foreignKey(connection: Connection, name: String, keys: List<KeyColumn>): ForeignKeyDefinition {
        var key: ForeignKeyDefinition? = null
        query(
            connection,
            "SELECT * FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = CURRENT_SCHEMA() AND CONSTRAINT_NAME = ?",
            name,
        ) { row ->
            if (row.getString("UNIQUE_CONSTRAINT_SCHEMA") != connection.schema) unsupported("cross-schema foreign key `$name`")
            val targets = keyColumns(connection, row.getString("UNIQUE_CONSTRAINT_NAME"))
            key = ForeignKeyDefinition(
                name,
                keys.map { it.column },
                targets.first().table,
                keys.map { targets[it.targetPosition - 1].column },
                action(row.getString("DELETE_RULE")),
                action(row.getString("UPDATE_RULE")),
            )
        }
        return key ?: throw VolanMigrationException("H2 foreign key `$name` has no referenced constraint.")
    }

    private fun action(sql: String): ForeignKeyAction = when (sql) {
        "CASCADE" -> ForeignKeyAction.CASCADE
        "SET NULL" -> ForeignKeyAction.SET_NULL
        "SET DEFAULT" -> ForeignKeyAction.SET_DEFAULT
        "RESTRICT", "NO ACTION" -> ForeignKeyAction.RESTRICT
        else -> unsupported("foreign key action `$sql`")
    }

    private fun indexes(connection: Connection, table: String): List<IndexDefinition> = buildList {
        query(
            connection,
            "SELECT * FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_SCHEMA = CURRENT_SCHEMA() AND TABLE_NAME = ? ORDER BY INDEX_NAME",
            table,
        ) { row ->
            if (!row.getBoolean("IS_GENERATED")) {
                val name = row.getString("INDEX_NAME")
                val unique = row.getString("INDEX_TYPE_NAME") == "UNIQUE INDEX"
                if (row.getString("INDEX_TYPE_NAME") !in setOf("INDEX", "UNIQUE INDEX") ||
                    (unique && row.getString("NULLS_DISTINCT") != "YES")
                ) {
                    unsupported("index `$name` with unsupported method or null semantics")
                }
                val columns = ArrayList<String>()
                query(
                    connection,
                    "SELECT * FROM INFORMATION_SCHEMA.INDEX_COLUMNS " +
                        "WHERE INDEX_SCHEMA = CURRENT_SCHEMA() AND INDEX_NAME = ? ORDER BY ORDINAL_POSITION",
                    name,
                ) { entry ->
                    if (entry.getString("ORDERING_SPECIFICATION") != "ASC" || entry.getString("NULL_ORDERING") !in listOf(null, "FIRST")) {
                        unsupported("descending or custom null ordering on index `$name`")
                    }
                    columns += entry.getString("COLUMN_NAME")
                }
                add(IndexDefinition(name, columns, unique))
            }
        }
    }

    private fun query(connection: Connection, sql: String, vararg values: String, read: (ResultSet) -> Unit) {
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows -> while (rows.next()) read(rows) }
        }
    }

    private data class KeyColumn(
        val table: String,
        val column: String,
        val targetPosition: Int,
    )

    private companion object {
        private const val MAX_ARRAY = 65_536
        private const val DEFAULT_IDENTITY_CACHE = 32L
        private val TEXT_LITERAL = Regex("'((?:''|[^'])*)'")
        private fun unsupported(definition: String): Nothing =
            throw VolanMigrationException("H2 cannot preserve $definition in schema.volan; introspection was refused.")
    }
}
