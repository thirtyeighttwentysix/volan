package io.github.thirtyeighttwentysix.volan.migrate

import java.sql.Connection
import java.sql.SQLException

internal fun Connection.isSqlite(): Boolean = metaData.databaseProductName == "SQLite"

/** Lock before introspection; disable FKs before BEGIN, check them before COMMIT, restore their setting. */
internal fun <T> sqliteMigrationTransaction(connection: Connection, block: () -> T): T {
    if (!connection.autoCommit) throw VolanMigrationException("Migrations require a connection with auto-commit enabled.")
    val foreignKeys = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA foreign_keys").use {
            it.next()
            it.getInt(1)
        }
    }
    var started = false
    var committed = false
    try {
        connection.createStatement().use {
            it.execute("PRAGMA foreign_keys = OFF")
            it.execute("BEGIN IMMEDIATE")
        }
        started = true
        val result = block()
        verifySqliteForeignKeys(connection)
        connection.createStatement().use { statement ->
            statement.execute("COMMIT")
        }
        committed = true
        return result
    } catch (failure: SQLException) {
        throw VolanMigrationException("SQLite migration failed and was rolled back: ${failure.message}", failure)
    } finally {
        try {
            if (started && !committed) connection.createStatement().use { it.execute("ROLLBACK") }
        } finally {
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = $foreignKeys") }
        }
    }
}

private fun verifySqliteForeignKeys(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA foreign_key_check").use {
            if (it.next()) {
                throw VolanMigrationException("SQLite foreign key check failed for ${it.getString(1)}; migration was rolled back.")
            }
        }
    }
}
