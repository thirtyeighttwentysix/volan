package io.github.thirtyeighttwentysix.volan.runtime

import io.github.thirtyeighttwentysix.volan.Json
import org.jspecify.annotations.NullMarked
import org.jspecify.annotations.Nullable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * One row of a result, read by column name.
 *
 * Generated mappers call these directly. There is a required and an optional accessor for each type
 * rather than one nullable accessor, so that a mapper for a non-null column produces a non-null value
 * without a check at every field.
 */
@Suppress("TooManyFunctions")
@NullMarked
public interface Row {
    /** Whether [column] holds null in this row. */
    public fun isNull(column: String): Boolean

    /** Reads a non-null text column. */
    public fun getString(column: String): String

    /** Reads a text column that may be null. */
    public fun getStringOrNull(column: String): @Nullable String?

    /** Reads a non-null 32-bit integer column. */
    public fun getInt(column: String): Int

    /** Reads a 32-bit integer column that may be null. */
    public fun getIntOrNull(column: String): @Nullable Int?

    /** Reads a non-null 64-bit integer column. */
    public fun getLong(column: String): Long

    /** Reads a 64-bit integer column that may be null. */
    public fun getLongOrNull(column: String): @Nullable Long?

    /** Reads a non-null 32-bit floating point column. */
    public fun getFloat(column: String): Float

    /** Reads a 32-bit floating point column that may be null. */
    public fun getFloatOrNull(column: String): @Nullable Float?

    /** Reads a non-null 64-bit floating point column. */
    public fun getDouble(column: String): Double

    /** Reads a 64-bit floating point column that may be null. */
    public fun getDoubleOrNull(column: String): @Nullable Double?

    /** Reads a non-null exact decimal column. */
    public fun getDecimal(column: String): BigDecimal

    /** Reads an exact decimal column that may be null. */
    public fun getDecimalOrNull(column: String): @Nullable BigDecimal?

    /** Reads a non-null boolean column. */
    public fun getBoolean(column: String): Boolean

    /** Reads a boolean column that may be null. */
    public fun getBooleanOrNull(column: String): @Nullable Boolean?

    /** Reads a non-null timestamp column. */
    public fun getInstant(column: String): Instant

    /** Reads a timestamp column that may be null. */
    public fun getInstantOrNull(column: String): @Nullable Instant?

    /** Reads a non-null date column. */
    public fun getLocalDate(column: String): LocalDate

    /** Reads a date column that may be null. */
    public fun getLocalDateOrNull(column: String): @Nullable LocalDate?

    /** Reads a non-null time column. */
    public fun getLocalTime(column: String): LocalTime

    /** Reads a time column that may be null. */
    public fun getLocalTimeOrNull(column: String): @Nullable LocalTime?

    /** Reads a non-null UUID column. */
    public fun getUuid(column: String): UUID

    /** Reads a UUID column that may be null. */
    public fun getUuidOrNull(column: String): @Nullable UUID?

    /** Reads a non-null binary column. */
    public fun getBytes(column: String): ByteArray

    /** Reads a binary column that may be null. */
    public fun getBytesOrNull(column: String): @Nullable ByteArray?

    /** Reads a non-null JSON column. */
    public fun getJson(column: String): Json

    /** Reads a JSON column that may be null. */
    public fun getJsonOrNull(column: String): @Nullable Json?

    /**
     * Reads a non-null array column, element by element.
     *
     * The elements come back as the driver produced them; a generated mapper casts each to the type
     * the schema declared, which is the one place where the column type is known.
     */
    public fun getScalarList(column: String): List<@Nullable Any?>

    /** Reads an array column that may be null. */
    public fun getScalarListOrNull(column: String): @Nullable List<@Nullable Any?>?
}

/**
 * Turns one [Row] into a value.
 *
 * Generated mappers are straight-line code: one positional read per column, no reflection, no
 * annotation lookup. This is why mapping costs roughly what a hand-written loop costs.
 */
@NullMarked
public fun interface RowMapper<T : @Nullable Any?> {
    /** Reads one row. */
    public fun map(row: Row): T
}
