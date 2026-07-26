package io.github.thirtyeighttwentysix.volan.runtime

import io.github.thirtyeighttwentysix.volan.Json
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * A [Row] over a JDBC result.
 *
 * Values are read by column name straight from the driver: no map is built, no boxing beyond what the
 * driver already did, and no reflection is involved anywhere.
 */
internal class JdbcRow(private val result: ResultSet) : Row {
    override fun isNull(column: String): Boolean {
        result.getObject(column)
        return result.wasNull()
    }

    override fun getString(column: String): String = required(column, result.getString(column))

    override fun getStringOrNull(column: String): String? = result.getString(column)

    override fun getInt(column: String): Int = result.getInt(column).also { requirePresent(column) }

    override fun getIntOrNull(column: String): Int? = result.getInt(column).takeUnless { result.wasNull() }

    override fun getLong(column: String): Long = result.getLong(column).also { requirePresent(column) }

    override fun getLongOrNull(column: String): Long? = result.getLong(column).takeUnless { result.wasNull() }

    override fun getFloat(column: String): Float = result.getFloat(column).also { requirePresent(column) }

    override fun getFloatOrNull(column: String): Float? = result.getFloat(column).takeUnless { result.wasNull() }

    override fun getDouble(column: String): Double = result.getDouble(column).also { requirePresent(column) }

    override fun getDoubleOrNull(column: String): Double? = result.getDouble(column).takeUnless { result.wasNull() }

    override fun getDecimal(column: String): BigDecimal = required(column, result.getBigDecimal(column))

    override fun getDecimalOrNull(column: String): BigDecimal? = result.getBigDecimal(column)

    override fun getBoolean(column: String): Boolean = result.getBoolean(column).also { requirePresent(column) }

    override fun getBooleanOrNull(column: String): Boolean? = result.getBoolean(column).takeUnless { result.wasNull() }

    override fun getInstant(column: String): Instant = required(column, getInstantOrNull(column))

    override fun getInstantOrNull(column: String): Instant? = when (val value = result.getObject(column)) {
        null -> null
        is Timestamp -> value.toInstant()
        is java.time.OffsetDateTime -> value.toInstant()
        is java.time.LocalDateTime -> value.toInstant(java.time.ZoneOffset.UTC)
        is Instant -> value
        else -> throw VolanMappingException(
            "`$column` holds ${value.javaClass.name}, which Volan cannot read as a moment in time.",
        )
    }

    override fun getLocalDate(column: String): LocalDate = required(column, getLocalDateOrNull(column))

    override fun getLocalDateOrNull(column: String): LocalDate? = result.getObject(column, LocalDate::class.java)

    override fun getLocalTime(column: String): LocalTime = required(column, getLocalTimeOrNull(column))

    override fun getLocalTimeOrNull(column: String): LocalTime? = result.getObject(column, LocalTime::class.java)

    override fun getUuid(column: String): UUID = required(column, getUuidOrNull(column))

    override fun getUuidOrNull(column: String): UUID? = when (val value = result.getObject(column)) {
        null -> null
        is UUID -> value
        is String -> UUID.fromString(value)
        else -> throw VolanMappingException("`$column` holds ${value.javaClass.name}, which Volan cannot read as a UUID.")
    }

    override fun getBytes(column: String): ByteArray = required(column, result.getBytes(column))

    override fun getBytesOrNull(column: String): ByteArray? = result.getBytes(column)

    override fun getJson(column: String): Json = required(column, getJsonOrNull(column))

    override fun getJsonOrNull(column: String): Json? = result.getString(column)?.let { Json.of(it) }

    override fun getScalarList(column: String): List<Any?> = required(column, getScalarListOrNull(column))

    override fun getScalarListOrNull(column: String): List<Any?>? {
        val array = result.getArray(column) ?: return null
        return try {
            (array.array as Array<*>).toList()
        } finally {
            array.free()
        }
    }

    /**
     * Fails when a column the schema says is not nullable turned out to be null.
     *
     * Reaching this means the database and the schema disagree, which is worth saying loudly rather
     * than turning into a null-pointer exception three frames later.
     */
    private fun <T : Any> required(column: String, value: T?): T = value ?: throw VolanMappingException(
        "`$column` is null in the database, but the schema declares it as required.",
    )

    private fun requirePresent(column: String) {
        if (result.wasNull()) {
            throw VolanMappingException("`$column` is null in the database, but the schema declares it as required.")
        }
    }
}
