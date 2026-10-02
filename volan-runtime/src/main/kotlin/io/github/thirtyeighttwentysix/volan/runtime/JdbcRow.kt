package io.github.thirtyeighttwentysix.volan.runtime

import io.github.thirtyeighttwentysix.volan.Json
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
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
internal class JdbcRow(private val result: ResultSet, private val timestampWithoutTimeZone: Boolean = false) : Row {
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
        is Timestamp -> if (timestampWithoutTimeZone) value.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC) else value.toInstant()
        is java.time.OffsetDateTime -> value.toInstant()
        is java.time.LocalDateTime -> value.toInstant(java.time.ZoneOffset.UTC)
        is Instant -> value
        is String -> Instant.parse(value)
        else -> throw VolanMappingException(
            "`$column` holds ${value.javaClass.name}, which Volan cannot read as a moment in time.",
        )
    }

    override fun getLocalDate(column: String): LocalDate = required(column, getLocalDateOrNull(column))

    override fun getLocalDateOrNull(column: String): LocalDate? = when (val value = result.getObject(column)) {
        null -> null
        is LocalDate -> value
        is java.sql.Date -> result.getObject(column, LocalDate::class.java)
        is String -> LocalDate.parse(value)
        else -> throw VolanMappingException("`$column` holds ${value.javaClass.name}, which Volan cannot read as a date.")
    }

    override fun getLocalTime(column: String): LocalTime = required(column, getLocalTimeOrNull(column))

    override fun getLocalTimeOrNull(column: String): LocalTime? = when (val value = result.getObject(column)) {
        null -> null
        is LocalTime -> value
        is java.sql.Time -> result.getObject(column, LocalTime::class.java)
        is String -> LocalTime.parse(value)
        else -> throw VolanMappingException("`$column` holds ${value.javaClass.name}, which Volan cannot read as a time of day.")
    }

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

    /** JDBC's legacy date/time objects can shift dates or discard fractional seconds. */
    internal fun aggregateValue(column: String): Any? = when (val value = result.getObject(column)) {
        is Timestamp -> if (timestampWithoutTimeZone) getInstantOrNull(column) else value
        is java.sql.Date -> result.getObject(column, LocalDate::class.java)
        is java.sql.Time -> result.getObject(column, LocalTime::class.java)
        else -> value
    }

    override fun getScalarListOrNull(column: String): List<Any?>? {
        val array = result.getArray(column) ?: return null
        return try {
            array.resultSet.use { values ->
                val elements = ArrayList<Any?>()
                val name = values.metaData.getColumnLabel(2)
                val row = JdbcRow(values)
                while (values.next()) elements.add(arrayElement(row, values, name, array.baseType, array.baseTypeName))
                elements
            }
        } finally {
            array.free()
        }
    }

    private fun arrayElement(row: JdbcRow, values: ResultSet, name: String, type: Int, typeName: String): Any? = when {
        typeName.equals("JSON", ignoreCase = true) || typeName.equals("JSONB", ignoreCase = true) -> row.getJsonOrNull(name)
        typeName.equals("UUID", ignoreCase = true) -> row.getUuidOrNull(name)
        else -> when (type) {
            Types.DATE -> row.getLocalDateOrNull(name)
            Types.TIME -> row.getLocalTimeOrNull(name)
            Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> row.getInstantOrNull(name)
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> row.getBytesOrNull(name)
            else -> values.getObject(name)
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
