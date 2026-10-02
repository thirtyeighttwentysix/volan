package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer
import io.github.thirtyeighttwentysix.volan.ir.Provider
import io.github.thirtyeighttwentysix.volan.ir.Schema
import java.sql.Connection
import java.sql.SQLException

/** The library operations behind `db pull` and `db push`, without owning files or connections. */
public class DatabaseSync(private val reader: DatabaseReader, private val dialect: DdlRenderer) {
    /** Reads the current database as a validated schema.volan document. */
    public fun pull(connection: Connection): String =
        SchemaWriter.write(reader.read(connection), Provider.entries.first { it.id == dialect.id })

    /** Describes differences from [expected], including changes made outside Volan. Does not write. */
    public fun drift(connection: Connection, expected: DatabaseSchema): MigrationPlan = diff(expected, reader.read(connection))

    /** Previews the changes needed to match [schema]. Does not write to the database or journal. */
    public fun plan(connection: Connection, schema: Schema): MigrationPlan {
        if (schema.datasource.provider.id != dialect.id) {
            throw VolanMigrationException("The schema provider does not match the ${dialect.id} migration dialect.")
        }
        return diff(reader.read(connection), SchemaMapper.map(schema))
    }

    private fun diff(from: DatabaseSchema, to: DatabaseSchema): MigrationPlan =
        if (dialect.id == Provider.SQLITE.id) SqliteDiffer.diff(from, to) else SchemaDiffer.diff(from, to)

    /**
     * Applies the desired schema atomically, without adding migration history.
     * Warning-bearing changes require [acceptWarnings]. The connection must have auto-commit enabled;
     * this operation never commits a caller's existing transaction.
     */
    @JvmOverloads
    public fun push(connection: Connection, schema: Schema, acceptWarnings: Boolean = false): MigrationPlan {
        if (connection.isH2()) {
            throw VolanMigrationException("Automatic H2 database push is not yet supported; use reviewed versioned migrations.")
        }
        return withMigrationLock(connection) {
            if (connection.isSqlite()) {
                return@withMigrationLock sqliteMigrationTransaction(connection) {
                    val plan = plan(connection, schema)
                    review(plan, acceptWarnings)
                    connection.createStatement().use { statement -> plan.render(dialect).forEach { statement.execute(it) } }
                    verify(connection, schema)
                    plan
                }
            }
            val plan = plan(connection, schema)
            review(plan, acceptWarnings)
            val statements = plan.render(dialect)
            connection.autoCommit = false
            var committed = false
            try {
                connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
                verify(connection, schema)
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

    private fun review(plan: MigrationPlan, acceptWarnings: Boolean) {
        if (plan.isDestructive && !acceptWarnings) {
            throw VolanMigrationException("Review the migration warnings before applying:\n" + plan.warnings.joinToString("\n"))
        }
    }

    private fun verify(connection: Connection, schema: Schema) {
        if (!diff(reader.read(connection), SchemaMapper.map(schema)).isEmpty) {
            throw VolanMigrationException("The applied schema does not match the requested schema; push was rolled back.")
        }
    }
}

/** Serializes migration writers, including concurrent processes. */
internal fun <T> withMigrationLock(connection: Connection, block: () -> T): T {
    requireMigrationProvider(connection)
    if (!connection.autoCommit) throw VolanMigrationException("Migrations require a connection with auto-commit enabled.")
    if (connection.isH2()) return withH2MigrationLock(connection, block)
    val postgres = connection.metaData.databaseProductName == "PostgreSQL"
    if (postgres) migrationLock(connection, "pg_advisory_lock")
    try {
        return block()
    } finally {
        if (postgres) migrationLock(connection, "pg_advisory_unlock")
    }
}

internal fun requireMigrationProvider(connection: Connection) {
    val provider = connection.metaData.databaseProductName
    if (provider !in listOf("PostgreSQL", "SQLite", "H2")) {
        throw VolanMigrationException("Versioned migrations and database push are not yet supported for $provider.")
    }
}

private fun migrationLock(connection: Connection, function: String) {
    connection.createStatement().use {
        it.execute("SELECT $function(hashtext(current_database()), hashtext(current_schema() || ':volan:migrate'))")
    }
}
