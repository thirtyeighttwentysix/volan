package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer
import io.github.thirtyeighttwentysix.volan.ir.Schema
import java.sql.Connection
import java.sql.SQLException

/** The library operations behind `db pull` and `db push`, without owning files or connections. */
public class DatabaseSync(private val reader: DatabaseReader, private val dialect: DdlRenderer) {
    /** Reads the current database as a validated schema.volan document. */
    public fun pull(connection: Connection): String = SchemaWriter.write(reader.read(connection))

    /** Describes differences from [expected], including changes made outside Volan. Does not write. */
    public fun drift(connection: Connection, expected: DatabaseSchema): MigrationPlan = SchemaDiffer.diff(expected, reader.read(connection))

    /** Previews the changes needed to match [schema]. Does not write to the database or journal. */
    public fun plan(connection: Connection, schema: Schema): MigrationPlan =
        SchemaDiffer.diff(reader.read(connection), SchemaMapper.map(schema))

    /**
     * Applies the desired schema atomically, without adding migration history.
     * Warning-bearing changes require [acceptWarnings]. The connection must have auto-commit enabled;
     * this operation never commits a caller's existing transaction.
     */
    @JvmOverloads
    public fun push(connection: Connection, schema: Schema, acceptWarnings: Boolean = false): MigrationPlan =
        withMigrationLock(connection) {
            val plan = plan(connection, schema)
            if (plan.isDestructive && !acceptWarnings) {
                throw VolanMigrationException("Review the migration warnings before applying:\n" + plan.warnings.joinToString("\n"))
            }
            val statements = plan.render(dialect)
            connection.autoCommit = false
            var committed = false
            try {
                connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
                val remaining = SchemaDiffer.diff(reader.read(connection), SchemaMapper.map(schema))
                if (!remaining.isEmpty) {
                    throw VolanMigrationException(
                        "The applied schema does not match the requested schema; push was rolled back.",
                    )
                }
                connection.commit()
                committed = true
            } catch (failure: SQLException) {
                throw VolanMigrationException("Database push failed and was rolled back: ${failure.message}", failure)
            } finally {
                if (!committed) connection.rollback()
                connection.autoCommit = true
            }
            plan
        }
}

/** Serializes PostgreSQL migration writers in this schema, including concurrent processes. */
internal fun <T> withMigrationLock(connection: Connection, block: () -> T): T {
    if (!connection.autoCommit) throw VolanMigrationException("Migrations require a connection with auto-commit enabled.")
    val postgres = connection.metaData.databaseProductName == "PostgreSQL"
    if (postgres) migrationLock(connection, "pg_advisory_lock")
    try {
        return block()
    } finally {
        if (postgres) migrationLock(connection, "pg_advisory_unlock")
    }
}

private fun migrationLock(connection: Connection, function: String) {
    connection.createStatement().use {
        it.execute("SELECT $function(hashtext(current_database()), hashtext(current_schema() || ':volan:migrate'))")
    }
}
