package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer
import io.github.thirtyeighttwentysix.volan.dialect.DdlStatement
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

    private fun diff(from: DatabaseSchema, to: DatabaseSchema): MigrationPlan = when (dialect.id) {
        Provider.SQLITE.id -> SqliteDiffer.diff(from, to)
        Provider.MYSQL.id, Provider.MARIADB.id -> MySqlDiffer.diff(from, to)
        else -> SchemaDiffer.diff(from, to)
    }

    /**
     * Applies the desired schema without adding versioned migration history.
     * PostgreSQL and SQLite roll back on failure. H2, MySQL and MariaDB retain committed DDL
     * and a durable push record; repair a failed push before calling [resolvePush].
     * Warning-bearing changes require [acceptWarnings]. The connection must have auto-commit enabled;
     * this operation never commits a caller's existing transaction.
     */
    @JvmOverloads
    public fun push(connection: Connection, schema: Schema, acceptWarnings: Boolean = false): MigrationPlan {
        return withMigrationLock(connection) {
            if (connection.hasCommittedDdl()) return@withMigrationLock committedPush(connection, schema, acceptWarnings)
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

    private fun committedPush(connection: Connection, schema: Schema, acceptWarnings: Boolean): MigrationPlan {
        val journal = MigrationJournal(PUSH_TABLE)
        refuseUnfinishedPush(journal, connection)
        if (MigrationJournal().read(connection).any { !it.isFinished }) {
            throw VolanMigrationException("An unfinished versioned migration blocks database push; repair migration history first.")
        }
        val plan = plan(connection, schema)
        review(plan, acceptWarnings)
        if (connection.isMySql()) refuseImplicitFill(connection, plan)
        val sql = plan.toSql(dialect)
        if (plan.isEmpty) return plan
        journal.ensure(connection)
        val migration = MigrationFile("push_${fingerprint(schema)}_${java.util.UUID.randomUUID()}", sql)
        CommittedMigration(journal, java.time.Clock.systemUTC()).run(connection, migration, "resolvePush with the original target schema") {
            if (!diff(reader.read(connection), SchemaMapper.map(schema)).isEmpty) {
                throw VolanMigrationException("Database push did not produce the requested schema. Inspect and repair the unfinished push.")
            }
        }
        return plan
    }

    /** Records a manually repaired nontransactional push after checking its original target schema. */
    public fun resolvePush(connection: Connection, schema: Schema): Unit = withMigrationLock(connection) {
        if (!connection.hasCommittedDdl()) {
            throw VolanMigrationException(
                "Push recovery is only available for nontransactional DDL providers.",
            )
        }
        val journal = MigrationJournal(PUSH_TABLE)
        val unfinished = journal.read(connection).filterNot { it.isFinished }
        if (unfinished.isEmpty()) throw VolanMigrationException("There is no unfinished database push to resolve.")
        if (schema.datasource.provider.id != dialect.id || unfinished.any { !it.id.startsWith("push_${fingerprint(schema)}_") }) {
            throw VolanMigrationException("Restore the original target schema before resolving the unfinished push.")
        }
        if (!plan(
                connection,
                schema,
            ).isEmpty
        ) {
            throw VolanMigrationException("Repair the database to the original target schema before resolving push.")
        }
        unfinished.forEach { journal.finish(connection, it.id, java.time.Instant.now(), it.appliedSteps) }
    }

    private fun fingerprint(schema: Schema): String = MigrationFile.checksumOf(SchemaMapper.map(schema).toString())

    private fun refuseImplicitFill(connection: Connection, plan: MigrationPlan) {
        val existing = reader.read(connection).tables.map { it.name }.toSet()
        plan.steps.mapNotNull { it.statement as? DdlStatement.AddColumn }.forEach { added ->
            if (added.table in existing && !added.column.nullable && added.column.default == null) {
                if (isPopulated(connection, added.table)) {
                    throw VolanMigrationException(
                        "Adding required `${added.table}.${added.column.name}` needs an explicit default on a populated table. " +
                            "Use a reviewed migration to backfill values before making the column required.",
                    )
                }
            }
        }
    }

    private fun isPopulated(connection: Connection, table: String): Boolean = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT 1 FROM ${dialect.quote(table)} LIMIT 1").use { it.next() }
    }

    private fun refuseUnfinishedPush(journal: MigrationJournal, connection: Connection) {
        if (journal.read(connection).any { !it.isFinished }) {
            throw VolanMigrationException(
                "An unfinished database push blocks further changes. Inspect and repair the database, then use resolvePush.",
            )
        }
    }

    internal companion object {
        const val PUSH_TABLE: String = "_volan_push"
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
    if (connection.isMySql()) return withMySqlMigrationLock(connection, block)
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
    if (provider !in listOf("PostgreSQL", "SQLite", "H2", "MySQL", "MariaDB")) {
        throw VolanMigrationException("Versioned migrations and database push are not yet supported for $provider.")
    }
}

private fun migrationLock(connection: Connection, function: String) {
    connection.createStatement().use {
        it.execute("SELECT $function(hashtext(current_database()), hashtext(current_schema() || ':volan:migrate'))")
    }
}
