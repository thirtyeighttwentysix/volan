package io.github.thirtyeighttwentysix.volan.migrate

import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant

/**
 * Where a database stands against the migrations on disk.
 *
 * @property applied the migrations this database has run and finished, oldest first.
 * @property pending the migrations on disk it has not run.
 * @property edited migrations it ran whose file has changed since. The database cannot be brought back
 *   in line by running them again, so this is reported rather than fixed.
 * @property missing migrations it ran that are no longer on disk.
 * @property unfinished migrations it started and did not finish.
 */
public data class MigrationStatus(
    public val applied: List<AppliedMigration>,
    public val pending: List<MigrationFile>,
    public val edited: List<String>,
    public val missing: List<String>,
    public val unfinished: List<String>,
) {
    /** Whether the database is where the migrations say it should be. */
    public val isUpToDate: Boolean get() = pending.isEmpty() && !hasDrifted

    /** Whether the history on disk and the history in the database disagree about what has happened. */
    public val hasDrifted: Boolean get() = edited.isNotEmpty() || missing.isNotEmpty() || unfinished.isNotEmpty()
}

/**
 * Runs the migrations a project has written, and says what it has run.
 *
 * Migrations are applied one at a time, each in its own transaction, in the order their names give.
 * A migration that fails leaves its own changes undone and the ones before it in place, and the record
 * of it stays behind unfinished — because a person has to decide what a half-finished migration means,
 * and pretending it never started would take that decision away from them.
 */
public class Migrator(
    private val directory: MigrationDirectory,
    private val journal: MigrationJournal = MigrationJournal(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Where [connection]'s database stands against the migrations on disk. */
    public fun status(connection: Connection): MigrationStatus {
        val files = directory.read()
        val applied = journal.read(connection)
        val byId = files.associateBy { it.id }
        val finished = applied.filter { it.isFinished }
        return MigrationStatus(
            applied = finished,
            pending = files.filter { file -> applied.none { it.id == file.id } },
            edited = finished.filter { record -> byId[record.id]?.checksum?.let { it != record.checksum } == true }.map { it.id },
            missing = finished.filter { byId[it.id] == null }.map { it.id },
            unfinished = applied.filterNot { it.isFinished }.map { it.id },
        )
    }

    /**
     * Runs every migration this database has not run, and returns them.
     *
     * @throws VolanMigrationException when the history on disk and the history in the database
     *   disagree. Applying migrations on top of a database whose past has changed underneath would
     *   produce a database neither of them describes.
     */
    public fun apply(connection: Connection): List<MigrationFile> {
        journal.ensure(connection)
        val status = status(connection)
        refuseDrift(status)
        return status.pending.map { migration ->
            run(connection, migration)
            migration
        }
    }

    private fun refuseDrift(status: MigrationStatus) {
        val problem = when {
            status.unfinished.isNotEmpty() ->
                "the migration ${describe(status.unfinished)} started and never finished, so what this " +
                    "database holds is not what any migration describes.\n" +
                    "  Look at what it did, put the database right, and record it as applied — or restore and start again."
            status.edited.isNotEmpty() ->
                "the migration ${describe(status.edited)} has changed since this database ran it, and running " +
                    "it again would not undo what the old version did.\n" +
                    "  Put the file back as it was and write a new migration for the change instead."
            status.missing.isNotEmpty() ->
                "this database ran the migration ${describe(status.missing)}, which is no longer on disk.\n" +
                    "  A database cannot be rebuilt from migrations that do not exist: restore the file, or " +
                    "start this database again from the migrations that do."
            else -> return
        }
        throw VolanMigrationException(problem)
    }

    /**
     * Runs one migration inside one transaction.
     *
     * The record of it is written by the same transaction as its statements wherever the database lets
     * DDL take part in one, so a migration that fails leaves behind neither its changes nor a claim to
     * have made them.
     */
    private fun run(connection: Connection, migration: MigrationFile) {
        val statements = split(migration.sql)
        val restore = connection.autoCommit
        connection.autoCommit = false
        try {
            journal.begin(connection, migration, clock.instant())
            connection.createStatement().use { statement ->
                statements.forEach { statement.execute(it) }
            }
            journal.finish(connection, migration.id, clock.instant(), statements.size)
            connection.commit()
        } catch (failure: SQLException) {
            connection.rollback()
            throw VolanMigrationException(
                "the migration `${migration.id}` failed and was rolled back: ${failure.message}\n" +
                    "  Nothing it asked for was applied. Fix the migration and run it again.",
                failure,
            )
        } finally {
            connection.autoCommit = restore
        }
    }

    /**
     * Splits a script into statements.
     *
     * A semicolon inside a string literal or a comment does not end a statement, which is the whole
     * reason this is not a call to `split(";")`.
     */
    internal fun split(sql: String): List<String> {
        val statements = ArrayList<String>()
        val current = StringBuilder()
        var index = 0
        var quote: Char? = null
        while (index < sql.length) {
            val character = sql[index]
            when {
                quote != null -> {
                    current.append(character)
                    if (character == quote) quote = null
                    index++
                }
                character == '\'' || character == '"' -> {
                    quote = character
                    current.append(character)
                    index++
                }
                character == '-' && sql.startsWith("--", index) -> {
                    val end = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length
                    index = end
                }
                character == ';' -> {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let { statements.add(it) }
                    current.setLength(0)
                    index++
                }
                else -> {
                    current.append(character)
                    index++
                }
            }
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let { statements.add(it) }
        return statements
    }

    private fun describe(ids: List<String>): String =
        ids.joinToString(", ") { "`$it`" } + if (ids.size == 1) "" else " (and the ones after them)"

    /** Records [migration] as applied without running it, for a database already in that shape. */
    public fun markApplied(connection: Connection, migration: MigrationFile) {
        journal.ensure(connection)
        journal.markApplied(connection, migration, clock.instant())
    }

    /** The moment this migrator would stamp a migration with, for a caller that names its own files. */
    public fun now(): Instant = clock.instant()
}
