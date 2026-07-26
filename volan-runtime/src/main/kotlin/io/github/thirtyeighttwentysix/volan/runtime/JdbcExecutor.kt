package io.github.thirtyeighttwentysix.volan.runtime

import io.github.thirtyeighttwentysix.volan.Json
import io.github.thirtyeighttwentysix.volan.VolanException
import io.github.thirtyeighttwentysix.volan.dialect.Dialect
import io.github.thirtyeighttwentysix.volan.dialect.SqlStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Runs query descriptions against a database over JDBC.
 *
 * Everything it does follows the same three steps: plan the description into Volan's SQL model, let
 * the dialect render it, then prepare, bind and execute. No SQL is written here, and no description is
 * interpreted by the dialect.
 */
internal class JdbcExecutor(
    private val connections: ConnectionSource,
    private val planner: QueryPlanner,
    private val dialect: Dialect,
    private val loader: RelationLoader,
    private val registry: TableRegistry,
    private val readers: Map<String, EntityReader<*>>,
    private val clock: Clock,
) : QueryExecutor,
    RelationSource,
    NestedWriteTarget {
    private val nested = NestedWriter(registry, this)

    @Suppress("UNCHECKED_CAST")
    override fun read(spec: QuerySpec, reader: EntityReader<*>): List<Any?> = findMany(spec, reader as RowMapper<Any?>)

    override fun joinPairs(
        table: String,
        localColumns: List<String>,
        targetColumns: List<String>,
        keys: List<List<Any?>>,
    ): List<Pair<List<Any?>, List<Any?>>> {
        val statement = dialect.render(planner.joinSelect(table, localColumns, targetColumns, keys))
        return query(statement, "reading the `$table` join table") { result ->
            val pairs = ArrayList<Pair<List<Any?>, List<Any?>>>()
            while (result.next()) {
                pairs.add(localColumns.map { result.getObject(it) } to targetColumns.map { result.getObject(it) })
            }
            pairs
        }
    }
    override fun <T> findMany(spec: QuerySpec, mapper: RowMapper<T>): List<T> {
        val rows = query(dialect.render(planner.select(spec)), "reading ${spec.model}") { result ->
            val read = ArrayList<T>()
            val row = JdbcRow(result)
            while (result.next()) read.add(mapper.map(row))
            read
        }
        return withRelations(spec, mapper, rows)
    }

    override fun <T> findFirst(spec: QuerySpec, mapper: RowMapper<T>): T? {
        val limited = spec.copy(pagination = spec.pagination.copy(take = 1))
        val row = query(dialect.render(planner.select(limited)), "reading ${spec.model}") { result ->
            if (result.next()) mapper.map(JdbcRow(result)) else null
        }
        return row?.let { withRelations(spec, mapper, listOf(it)).single() }
    }

    /**
     * Fills in whatever the query asked to `include`.
     *
     * Only an entity can hold a relation, so asking for one alongside a partial `select` is a question
     * with no answer rather than a feature that is missing.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> withRelations(spec: QuerySpec, mapper: RowMapper<T>, rows: List<T>): List<T> {
        if (spec.includes.isEmpty()) return rows
        val reader = mapper as? EntityReader<T> ?: throw VolanQueryException(
            "`${spec.model}` was read into a projection, which has nowhere to put the relations this query " +
                "asked to include.\n  Use `findMany` for rows with relations, and `projectMany` for chosen columns.",
            null,
        )
        return loader.load(rows, reader, spec.includes, this)
    }

    override fun count(spec: QuerySpec): Long = query(dialect.render(planner.count(spec)), "counting ${spec.model}") { result ->
        if (result.next()) result.getLong(1) else 0L
    }

    override fun exists(spec: QuerySpec): Boolean =
        query(dialect.render(planner.exists(spec)), "checking whether any ${spec.model} matches") { it.next() }

    /**
     * Writes one row, and whatever it asked to write alongside it.
     *
     * A create with nested writes runs in one transaction: a shape that is half written is worse than
     * one that was never written, and the caller cannot undo the half they got.
     */
    override fun <T> create(spec: CreateSpec, mapper: RowMapper<T>): T {
        requireReturning(spec.model, "create")
        if (spec.nested.isEmpty()) return insertOne(spec, mapper)
        @Suppress("UNCHECKED_CAST")
        return connections.transaction(Isolation.DEFAULT, RetryPolicy.NONE) { nested.write(spec).row as T }
    }

    private fun <T> insertOne(spec: CreateSpec, mapper: RowMapper<T>): T {
        val statement = dialect.render(planner.insert(listOf(spec), clock.instant()))
        return query(statement, "creating a ${spec.model}") { result ->
            if (result.next()) mapper.map(JdbcRow(result)) else throw missingReturn(spec.model, "create")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun insertPlain(spec: CreateSpec, keyColumns: List<String>): WrittenRow {
        val reader = readerFor(spec.model) as EntityReader<Any?>
        val row = insertOne(spec, reader)
        return WrittenRow(row, reader.key(row, keyColumns))
    }

    @Suppress("UNCHECKED_CAST")
    override fun findKey(model: String, filter: Filter?, keyColumns: List<String>): List<Any?>? {
        val reader = readerFor(model) as EntityReader<Any?>
        val row = findFirst(QuerySpec(model, filter), reader) ?: return null
        return reader.key(row, keyColumns)
    }

    override fun pointAt(model: String, filter: Filter?, columns: List<String>, key: List<Any?>): Long =
        updateMany(UpdateSpec(model, filter, columns.zip(key).toMap()))

    override fun pair(
        table: String,
        localColumns: List<String>,
        targetColumns: List<String>,
        local: List<Any?>,
        targets: List<List<Any?>>,
    ) {
        val statement = dialect.render(planner.joinInsert(table, localColumns, targetColumns, local, targets))
        update(statement, "linking rows through `$table`")
    }

    private fun readerFor(model: String): EntityReader<*> = readers[model] ?: throw VolanConfigurationException(
        "no reader was registered for `$model`, so its rows cannot be written as part of another write.",
    )

    override fun createMany(specs: List<CreateSpec>): Long {
        if (specs.isEmpty()) return 0
        val statement = dialect.render(planner.insert(specs, clock.instant()).copy(returning = emptyList()))
        return update(statement, "creating ${specs.size} ${specs.first().model} rows").toLong()
    }

    override fun <T> update(spec: UpdateSpec, mapper: RowMapper<T>): T {
        requireReturning(spec.model, "update")
        val statement = dialect.render(planner.update(spec, clock.instant(), returning = true))
        return query(statement, "updating a ${spec.model}") { result ->
            if (result.next()) {
                mapper.map(JdbcRow(result))
            } else {
                throw VolanNotFoundException(
                    spec.model,
                    "no ${spec.model} matched the `where` block of this update, so there was nothing to change.",
                )
            }
        }
    }

    override fun updateMany(spec: UpdateSpec): Long {
        val statement = dialect.render(planner.update(spec, clock.instant(), returning = false))
        return update(statement, "updating ${spec.model} rows").toLong()
    }

    override fun <T> upsert(spec: UpsertSpec, mapper: RowMapper<T>): T {
        val existing = findFirst(QuerySpec(spec.model, spec.filter), mapper)
        if (existing == null) {
            return create(CreateSpec(spec.model, spec.create), mapper)
        }
        return update(UpdateSpec(spec.model, spec.filter, spec.update), mapper)
    }

    override fun <T> delete(spec: DeleteSpec, mapper: RowMapper<T>): T {
        requireReturning(spec.model, "delete")
        val statement = dialect.render(planner.delete(spec, returning = true))
        return query(statement, "deleting a ${spec.model}") { result ->
            if (result.next()) {
                mapper.map(JdbcRow(result))
            } else {
                throw VolanNotFoundException(
                    spec.model,
                    "no ${spec.model} matched the `where` block of this delete, so there was nothing to remove.",
                )
            }
        }
    }

    override fun deleteMany(spec: DeleteSpec): Long {
        val statement = dialect.render(planner.delete(spec, returning = false))
        return update(statement, "deleting ${spec.model} rows").toLong()
    }

    /** Runs a caller-written statement, with the caller's values still bound as parameters. */
    fun <T> rawQuery(sql: String, parameters: List<Any?>, mapper: RowMapper<T>): List<T> =
        query(SqlStatement(sql, parameters), "running a raw query") { result ->
            val rows = ArrayList<T>()
            val row = JdbcRow(result)
            while (result.next()) rows.add(mapper.map(row))
            rows
        }

    /** Runs a caller-written statement that changes rows. */
    fun rawExecute(sql: String, parameters: List<Any?>): Long = update(SqlStatement(sql, parameters), "running a raw statement").toLong()

    private fun <T> query(statement: SqlStatement, context: String, read: (ResultSet) -> T): T = connections.use { connection ->
        prepare(connection, statement, context).use { prepared ->
            try {
                prepared.executeQuery().use(read)
            } catch (failure: SQLException) {
                throw SqlErrors.translate(failure, context)
            }
        }
    }

    private fun update(statement: SqlStatement, context: String): Int = connections.use { connection ->
        prepare(connection, statement, context).use { prepared ->
            try {
                prepared.executeUpdate()
            } catch (failure: SQLException) {
                throw SqlErrors.translate(failure, context)
            }
        }
    }

    private fun prepare(connection: Connection, statement: SqlStatement, context: String): PreparedStatement = try {
        connection.prepareStatement(statement.sql).also { bind(it, statement.parameters) }
    } catch (failure: SQLException) {
        throw SqlErrors.translate(failure, context)
    }

    /**
     * Binds the values of a statement.
     *
     * JDBC has no opinion about `java.time` beyond `setObject`, and drivers differ on which types they
     * accept, so the conversions Volan needs happen here rather than in every dialect.
     */
    private fun bind(statement: PreparedStatement, parameters: List<Any?>) {
        parameters.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                null -> statement.setObject(position, null)
                is Instant -> statement.setObject(position, Timestamp.from(value))
                is LocalDate -> statement.setObject(position, value)
                is LocalTime -> statement.setObject(position, value)
                is Json -> statement.setString(position, value.raw)
                is Enum<*> -> statement.setString(position, value.name)
                else -> statement.setObject(position, value)
            }
        }
    }

    private fun requireReturning(model: String, operation: String) {
        if (dialect.capabilities.returningClause) return
        throw VolanUnsupportedException(
            "`$operation` on `$model` reads the row back in the same statement, which ${dialect.id} cannot do.\n" +
                "  Reading it back with a follow-up select arrives with the other dialects in M8.",
        )
    }

    private fun missingReturn(model: String, operation: String): VolanException = VolanQueryException(
        "`$operation` on `$model` wrote a row but the database returned nothing to map, " +
            "which should not be possible and suggests a driver problem.",
        null,
    )
}
