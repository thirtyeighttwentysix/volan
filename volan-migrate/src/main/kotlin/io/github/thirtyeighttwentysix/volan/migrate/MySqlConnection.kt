package io.github.thirtyeighttwentysix.volan.migrate

import java.sql.Connection

internal fun Connection.isMySql(): Boolean = metaData.databaseProductName in setOf("MySQL", "MariaDB")

internal fun Connection.hasCommittedDdl(): Boolean = isH2() || isMySql()

/** Session-scoped named locks survive implicit DDL commits and release on disconnect. */
internal fun <T> withMySqlMigrationLock(connection: Connection, block: () -> T): T {
    connection.createStatement().use {
        it.execute("SET SESSION time_zone = '+00:00'")
        it.execute("SET SESSION sql_mode = CONCAT_WS(',', @@sql_mode, 'ANSI_QUOTES,NO_BACKSLASH_ESCAPES,STRICT_TRANS_TABLES')")
    }
    val name = lockName(connection)
    if (namedLock(connection, "GET_LOCK", name) !=
        1
    ) {
        throw VolanMigrationException("Timed out waiting for the MySQL/MariaDB migration lock.")
    }
    val result = runCatching(block)
    val released = runCatching { namedLock(connection, "RELEASE_LOCK", name) }
    mergeReleaseFailure(result.exceptionOrNull(), released.exceptionOrNull())
    return result.getOrThrow()
}

private fun lockName(connection: Connection): String {
    val database = connection.catalog ?: throw VolanMigrationException("Select a MySQL/MariaDB database in the JDBC URL.")
    return MigrationFile.checksumOf("volan:migrate:$database")
}

private fun mergeReleaseFailure(original: Throwable?, release: Throwable?) {
    if (release != null) {
        if (original != null) original.addSuppressed(release) else throw release
    }
}

private fun namedLock(connection: Connection, function: String, name: String): Int? {
    val timeout = if (function == "GET_LOCK") ", 60" else ""
    connection.prepareStatement("SELECT $function(?$timeout)").use { statement ->
        statement.setString(1, name)
        statement.executeQuery().use { rows ->
            check(rows.next())
            val result = rows.getInt(1)
            return result.takeUnless { rows.wasNull() }
        }
    }
}

/** Journal identifiers remain valid without requiring a caller's session to enable ANSI_QUOTES. */
internal fun migrationSql(connection: Connection, text: String): String = if (connection.isMySql()) {
    text.replace(Regex("\"([A-Za-z_][A-Za-z0-9_]*)\"")) { "`${it.groupValues[1]}`" }
} else {
    text
}
