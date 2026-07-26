package io.github.thirtyeighttwentysix.volan.migrate

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

/**
 * A migration the database has a record of.
 *
 * @property id the migration's directory name.
 * @property checksum the checksum of the script as it was when it ran. An applied migration whose file
 *   no longer matches this is the whole reason the column exists.
 * @property startedAt when it began.
 * @property finishedAt when it finished, or `null` when it did not.
 * @property appliedSteps how many statements had run when it stopped, which is what a half-applied
 *   migration needs a person to know.
 */
public data class AppliedMigration(
    public val id: String,
    public val checksum: String,
    public val startedAt: Instant,
    public val finishedAt: Instant?,
    public val appliedSteps: Int,
) {
    /** Whether this migration ran to the end. */
    public val isFinished: Boolean get() = finishedAt != null
}

/**
 * The table a database keeps its migration history in.
 *
 * The history lives in the database rather than beside it because it is a fact about that database:
 * a copy restored from a backup carries its own history with it, and two databases fed the same
 * migrations can disagree about how far they got.
 */
public class MigrationJournal(private val table: String = DEFAULT_TABLE) {
    /** Creates the history table if this database has none. Safe to call every time. */
    public fun ensure(connection: Connection) {
        if (exists(connection)) return
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE "$table" (
                  "id" varchar(255) NOT NULL,
                  "checksum" varchar(64) NOT NULL,
                  "started_at" timestamp NOT NULL,
                  "finished_at" timestamp,
                  "applied_steps" integer NOT NULL DEFAULT 0,
                  CONSTRAINT "${table}_pkey" PRIMARY KEY ("id")
                )
                """.trimIndent(),
            )
        }
    }

    /** Whether this database has a history table at all, which is what tells a fresh database apart. */
    public fun exists(connection: Connection): Boolean =
        connection.metaData.getTables(null, null, table, arrayOf("TABLE")).use { it.next() }

    /** Everything the database has a record of, oldest first. */
    public fun read(connection: Connection): List<AppliedMigration> {
        if (!exists(connection)) return emptyList()
        val sql = "SELECT \"id\", \"checksum\", \"started_at\", \"finished_at\", \"applied_steps\" " +
            "FROM \"$table\" ORDER BY \"id\""
        return connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                val applied = ArrayList<AppliedMigration>()
                while (result.next()) {
                    applied.add(
                        AppliedMigration(
                            id = result.getString("id"),
                            checksum = result.getString("checksum"),
                            startedAt = result.getTimestamp("started_at").toInstant(),
                            finishedAt = result.getTimestamp("finished_at")?.toInstant(),
                            appliedSteps = result.getInt("applied_steps"),
                        ),
                    )
                }
                applied
            }
        }
    }

    /** Records that a migration has begun, before its first statement runs. */
    public fun begin(connection: Connection, migration: MigrationFile, at: Instant) {
        val sql = """INSERT INTO "$table" ("id", "checksum", "started_at", "applied_steps") VALUES (?, ?, ?, 0)"""
        connection.prepareStatement(sql).use { statement ->
            var parameter = 0
            statement.setString(++parameter, migration.id)
            statement.setString(++parameter, migration.checksum)
            statement.setTimestamp(++parameter, Timestamp.from(at))
            statement.executeUpdate()
        }
    }

    /** Records that a migration has finished, and how many statements it ran. */
    public fun finish(connection: Connection, id: String, at: Instant, steps: Int) {
        val sql = """UPDATE "$table" SET "finished_at" = ?, "applied_steps" = ? WHERE "id" = ?"""
        connection.prepareStatement(sql).use { statement ->
            var parameter = 0
            statement.setTimestamp(++parameter, Timestamp.from(at))
            statement.setInt(++parameter, steps)
            statement.setString(++parameter, id)
            statement.executeUpdate()
        }
    }

    /**
     * Marks a migration as finished without running it.
     *
     * The escape hatch for a database that was brought to the right shape some other way — restored
     * from a dump, changed by hand — where re-running the migration would fail rather than help.
     */
    public fun markApplied(connection: Connection, migration: MigrationFile, at: Instant) {
        begin(connection, migration, at)
        finish(connection, migration.id, at, 0)
    }

    public companion object {
        /** The table migrations are recorded in. */
        public const val DEFAULT_TABLE: String = "_volan_migrations"
    }
}
