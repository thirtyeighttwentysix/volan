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
    private val clock: Clock,
) : QueryExecutor {
    override fun <T> findMany(spec: QuerySpec, mapper: RowMapper<T>): List<T> =
        query(dialect.render(planner.select(spec)), "reading ${spec.model}") { result ->
            val rows = ArrayList<T>()
            val row = JdbcRow(result)
            while (result.next()) rows.add(mapper.map(row))
            rows
        }

    override fun <T> findFirst(spec: QuerySpec, mapper: RowMapper<T>): T? {
        val limited = spec.copy(pagination = spec.pagination.copy(take = 1))
        return query(dialect.render(planner.select(limited)), "reading ${spec.model}") { result ->
            if (result.next()) mapper.map(JdbcRow(result)) else null
        }
    }

    override fun count(spec: QuerySpec): Long = query(dialect.render(planner.count(spec)), "counting ${spec.model}") { result ->
        if (result.next()) result.getLong(1) else 0L
    }

    override fun exists(spec: QuerySpec): Boolean =
        query(dialect.render(planner.exists(spec)), "checking whether any ${spec.model} matches") { it.next() }

    override fun <T> create(spec: CreateSpec, mapper: RowMapper<T>): T {
        requireReturning(spec.model, "create")
        val statement = dialect.render(planner.insert(listOf(spec), clock.instant()))
        return query(statement, "creating a ${spec.model}") { result ->
            if (result.next()) mapper.map(JdbcRow(result)) else throw missingReturn(spec.model, "create")
        }
    }

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
