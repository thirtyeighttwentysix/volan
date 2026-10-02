package io.github.thirtyeighttwentysix.volan.migrate

import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.util.Locale

/** H2 DDL commits implicitly, so neither a transaction nor its row locks span a migration. */
internal fun Connection.isH2(): Boolean = metaData.databaseProductName == "H2"

/** Exclusive mode survives DDL commits and is released by H2 if the owning session disconnects. */
internal fun <T> withH2MigrationLock(connection: Connection, block: () -> T): T {
    try {
        connection.createStatement().use { it.execute("SET EXCLUSIVE 1") }
    } catch (failure: SQLException) {
        throw VolanMigrationException("H2 migrations require an administrator connection and exclusive database access.", failure)
    }
    val outcome = runCatching(block)
    try {
        connection.createStatement().use { it.execute("SET EXCLUSIVE 0") }
    } catch (release: SQLException) {
        val failure = outcome.exceptionOrNull()
        if (failure != null) failure.addSuppressed(release) else throw release
    }
    return outcome.getOrThrow()
}

/** A durable start record blocks replay even if a process dies between a statement and its progress update. */
internal class CommittedMigration(private val journal: MigrationJournal, private val clock: Clock) {
    fun run(
        connection: Connection,
        migration: MigrationFile,
        recovery: String = "markApplied with the original migration",
        verify: () -> Unit = {},
    ) {
        val provider = connection.metaData.databaseProductName
        val statements = SqlScript(migration.sql, mysql = connection.isMySql()).split()
        validate(statements)
        var steps = 0
        try {
            journal.begin(connection, migration, clock.instant())
            connection.createStatement().use { statement ->
                statements.forEach { sql ->
                    statement.execute(sql)
                    steps++
                    journal.progress(connection, migration.id, steps)
                }
            }
            verify()
            journal.finish(connection, migration.id, clock.instant(), steps)
        } catch (failure: SQLException) {
            throw VolanMigrationException(
                "$provider migration `${migration.id}` failed after $steps completed statements: ${failure.message}\n" +
                    "  Earlier changes may already be committed. Inspect the database and unfinished journal entry, " +
                    "repair it to the intended final state, then use $recovery. " +
                    "Do not replay the script automatically.",
                failure,
            )
        }
    }

    private fun validate(statements: List<String>) {
        if (statements.any { COMMAND.find(it)?.value?.uppercase(Locale.ROOT) in FORBIDDEN }) {
            throw VolanMigrationException(
                "Migration scripts must not contain transaction/session control, RUNSCRIPT, EXECUTE or SHUTDOWN; " +
                    "Migrator owns auto-commit, the current schema and exclusive access.",
            )
        }
    }

    private companion object {
        private val COMMAND = Regex("^[A-Za-z]+")
        private val FORBIDDEN = setOf(
            "BEGIN", "START", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE", "PREPARE", "SET", "USE",
            "RUNSCRIPT", "EXECUTE", "SHUTDOWN", "LOCK", "UNLOCK",
        )
    }
}
