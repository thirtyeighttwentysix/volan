package verify

import io.github.thirtyeighttwentysix.volan.Json
import io.github.thirtyeighttwentysix.volan.runtime.AggregateSpec
import io.github.thirtyeighttwentysix.volan.runtime.CreateSpec
import io.github.thirtyeighttwentysix.volan.runtime.DeleteSpec
import io.github.thirtyeighttwentysix.volan.runtime.GroupRow
import io.github.thirtyeighttwentysix.volan.runtime.GroupSpec
import io.github.thirtyeighttwentysix.volan.runtime.QueryExecutor
import io.github.thirtyeighttwentysix.volan.runtime.QuerySpec
import io.github.thirtyeighttwentysix.volan.runtime.Row
import io.github.thirtyeighttwentysix.volan.runtime.RowMapper
import io.github.thirtyeighttwentysix.volan.runtime.UpdateSpec
import io.github.thirtyeighttwentysix.volan.runtime.UpsertSpec
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * An executor that records what it was asked for and answers from a fixed set of rows.
 *
 * The point of these tests is the description the generated client produces and the objects it maps
 * back, so the database is exactly the part that should not be here.
 */
class FakeExecutor(
    private val rows: List<Map<String, Any?>> = emptyList(),
    private val answers: Map<String, Any?> = emptyMap(),
) : QueryExecutor {
    val queries: MutableList<QuerySpec> = mutableListOf()
    val aggregates: MutableList<AggregateSpec> = mutableListOf()
    val groups: MutableList<GroupSpec> = mutableListOf()
    val creates: MutableList<CreateSpec> = mutableListOf()
    val updates: MutableList<UpdateSpec> = mutableListOf()
    val deletes: MutableList<DeleteSpec> = mutableListOf()
    val upserts: MutableList<UpsertSpec> = mutableListOf()

    /** The single query this executor was given, failing the test when it was given none or several. */
    val query: QuerySpec get() = queries.single()

    override fun <T> findMany(spec: QuerySpec, mapper: RowMapper<T>): List<T> {
        queries.add(spec)
        return rows.map { mapper.map(MapRow(it)) }
    }

    override fun <T> findFirst(spec: QuerySpec, mapper: RowMapper<T>): T? {
        queries.add(spec)
        return rows.firstOrNull()?.let { mapper.map(MapRow(it)) }
    }

    override fun count(spec: QuerySpec): Long {
        queries.add(spec)
        return rows.size.toLong()
    }

    override fun exists(spec: QuerySpec): Boolean {
        queries.add(spec)
        return rows.isNotEmpty()
    }

    override fun aggregate(spec: AggregateSpec): Map<String, Any?> {
        aggregates.add(spec)
        return spec.aggregations.associate { it.alias to answers[it.alias] }
    }

    override fun <K> groupBy(spec: GroupSpec, mapper: RowMapper<K>): List<GroupRow<K>> {
        groups.add(spec)
        return rows.map { GroupRow(mapper.map(MapRow(it)), spec.aggregations.associate { item -> item.alias to answers[item.alias] }) }
    }

    override fun <T> create(spec: CreateSpec, mapper: RowMapper<T>): T {
        creates.add(spec)
        return mapper.map(MapRow(rows.first()))
    }

    override fun createMany(specs: List<CreateSpec>): Long {
        creates.addAll(specs)
        return specs.size.toLong()
    }

    override fun <T> update(spec: UpdateSpec, mapper: RowMapper<T>): T {
        updates.add(spec)
        return mapper.map(MapRow(rows.first()))
    }

    /** The single change this executor was given, failing the test when it was given none or several. */
    val change: UpdateSpec get() = updates.single()

    override fun updateMany(spec: UpdateSpec): Long {
        updates.add(spec)
        return rows.size.toLong()
    }

    override fun <T> upsert(spec: UpsertSpec, mapper: RowMapper<T>): T {
        upserts.add(spec)
        return mapper.map(MapRow(rows.first()))
    }

    override fun <T> delete(spec: DeleteSpec, mapper: RowMapper<T>): T {
        deletes.add(spec)
        return mapper.map(MapRow(rows.first()))
    }

    override fun deleteMany(spec: DeleteSpec): Long {
        deletes.add(spec)
        return rows.size.toLong()
    }
}

/** A row backed by a map, so a test can write the values a query would have read. */
class MapRow(private val values: Map<String, Any?>) : Row {
    override fun isNull(column: String): Boolean = values[column] == null

    override fun getString(column: String): String = required(column)

    override fun getStringOrNull(column: String): String? = optional(column)

    override fun getInt(column: String): Int = required(column)

    override fun getIntOrNull(column: String): Int? = optional(column)

    override fun getLong(column: String): Long = required(column)

    override fun getLongOrNull(column: String): Long? = optional(column)

    override fun getFloat(column: String): Float = required(column)

    override fun getFloatOrNull(column: String): Float? = optional(column)

    override fun getDouble(column: String): Double = required(column)

    override fun getDoubleOrNull(column: String): Double? = optional(column)

    override fun getDecimal(column: String): BigDecimal = required(column)

    override fun getDecimalOrNull(column: String): BigDecimal? = optional(column)

    override fun getBoolean(column: String): Boolean = required(column)

    override fun getBooleanOrNull(column: String): Boolean? = optional(column)

    override fun getInstant(column: String): Instant = required(column)

    override fun getInstantOrNull(column: String): Instant? = optional(column)

    override fun getLocalDate(column: String): LocalDate = required(column)

    override fun getLocalDateOrNull(column: String): LocalDate? = optional(column)

    override fun getLocalTime(column: String): LocalTime = required(column)

    override fun getLocalTimeOrNull(column: String): LocalTime? = optional(column)

    override fun getUuid(column: String): UUID = required(column)

    override fun getUuidOrNull(column: String): UUID? = optional(column)

    override fun getBytes(column: String): ByteArray = required(column)

    override fun getBytesOrNull(column: String): ByteArray? = optional(column)

    override fun getJson(column: String): Json = required(column)

    override fun getJsonOrNull(column: String): Json? = optional(column)

    override fun getScalarList(column: String): List<Any?> = required(column)

    override fun getScalarListOrNull(column: String): List<Any?>? = optional(column)

    @Suppress("UNCHECKED_CAST")
    private fun <T> required(column: String): T =
        requireNotNull(values[column]) { "the fake row has no value for `$column`" } as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> optional(column: String): T? = values[column] as T?
}
