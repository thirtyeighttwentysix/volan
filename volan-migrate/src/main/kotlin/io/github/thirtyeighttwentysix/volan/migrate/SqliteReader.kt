package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.IndexDefinition
import java.sql.Connection
import java.sql.ResultSet

/** Reads main's ordinary SQLite tables; refuses objects a table rebuild could silently discard. */
public class SqliteReader(private val journalTable: String = MigrationJournal.DEFAULT_TABLE) : DatabaseReader {
    override fun read(connection: Connection): DatabaseSchema {
        if (connection.metaData.databaseProductName != "SQLite") {
            throw VolanMigrationException("SqliteReader requires a SQLite connection.")
        }
        rejectTemporaryObjects(connection)
        val tables = ArrayList<TableDefinition>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT type, name, sql FROM main.sqlite_schema WHERE name NOT GLOB 'sqlite_*' ORDER BY name",
            ).use { rows ->
                tables += readTables(rows)
            }
        }
        return DatabaseSchema(tables = tables.map { it.copy(indexes = indexes(connection, it.name)) })
    }

    private fun readTables(rows: ResultSet): List<TableDefinition> {
        val tables = ArrayList<TableDefinition>()
        while (rows.next()) {
            val name = rows.getString("name")
            when (rows.getString("type")) {
                "table" -> if (name != journalTable) tables += SqliteDeclarations(name).read(rows.getString("sql"))
                "index" -> Unit
                else -> SqliteSql.unsupported("view or trigger $name")
            }
        }
        return tables
    }

    private fun rejectTemporaryObjects(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM temp.sqlite_schema WHERE name NOT GLOB 'sqlite_*' LIMIT 1").use {
                if (it.next()) SqliteSql.unsupported("temporary object ${it.getString(1)}")
            }
            statement.executeQuery("PRAGMA database_list").use {
                while (it.next()) {
                    if (it.getString("name") !in listOf("main", "temp")) {
                        SqliteSql.unsupported("attached database ${it.getString("name")}")
                    }
                }
            }
        }
    }

    private fun indexes(connection: Connection, table: String): List<IndexDefinition> {
        val indexes = ArrayList<IndexDefinition>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA main.index_list(${quote(table)})").use { rows ->
                while (rows.next()) {
                    if (rows.getString("origin") != "c") continue
                    val name = rows.getString("name")
                    if (rows.getInt("partial") != 0) SqliteSql.unsupported("partial index $name")
                    indexes += IndexDefinition(name, indexColumns(connection, name), rows.getInt("unique") != 0)
                }
            }
        }
        return indexes.sortedBy { it.name }
    }

    private fun indexColumns(connection: Connection, index: String): List<String> = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA main.index_xinfo(${quote(index)})").use { rows ->
            buildList {
                while (rows.next()) {
                    if (rows.getInt("key") == 0) continue
                    if (rows.getInt("cid") < 0 || rows.getInt("desc") != 0 || rows.getString("coll") != "BINARY") {
                        SqliteSql.unsupported("expression, descending order or collation on index $index")
                    }
                    add(rows.getString("name"))
                }
            }
        }
    }

    private fun quote(name: String): String = "'" + name.replace("'", "''") + "'"
}
