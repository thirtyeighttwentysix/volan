package io.github.thirtyeighttwentysix.volan.runtime

import io.github.thirtyeighttwentysix.volan.VolanException
import java.math.BigDecimal
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The summaries a query can ask a database for. */
public enum class AggregateFunction {
    /** How many rows there are. */
    COUNT,

    /** The total. */
    SUM,

    /** The mean. */
    AVERAGE,

    /** The smallest value. */
    MINIMUM,

    /** The largest value. */
    MAXIMUM,
}

/**
 * One summary a query is asking for.
 *
 * @property function what to work out.
 * @property column the column to work it out over; `null` counts rows rather than values.
 * @property alias the name the answer comes back under.
 */
public data class Aggregation(
    public val function: AggregateFunction,
    public val column: String?,
    public val alias: String,
)

/**
 * A read that returns summaries rather than rows.
 *
 * @property model the model being summarised.
 * @property filter which rows to summarise; `null` for all of them.
 * @property aggregations what to work out, each under its own alias.
 */
public data class AggregateSpec(
    public val model: String,
    public val filter: Filter?,
    public val aggregations: List<Aggregation>,
)

/**
 * The receiver a generated `aggregate { … }` block runs against.
 *
 * Generated subclasses add the typed entry points; collecting what they ask for lives here.
 *
 * @param model the model being summarised.
 */
public abstract class AggregateScope protected constructor(private val model: String) {
    private val aggregations = ArrayList<Aggregation>()
    private var filter: Filter? = null

    /** Records the condition a `where { … }` block collected. */
    protected fun recordFilter(scope: FilterScope) {
        filter = Filter.all(listOfNotNull(filter, scope.build()))
    }

    /** Records one summary to work out. */
    protected fun record(function: AggregateFunction, column: String?, alias: String) {
        aggregations.removeAll { it.alias == alias }
        aggregations.add(Aggregation(function, column, alias))
    }

    /** Everything this scope collected, as a description. */
    public fun build(): AggregateSpec = AggregateSpec(model, filter, aggregations.toList())
}

/**
 * Reads the answers back out of what a database returned.
 *
 * A summary that was not asked for is absent rather than zero, because zero is a perfectly good answer
 * to a question that was asked. Generated accessors go through here so that reading one says what to
 * add to the query.
 */
public object AggregateValues {
    /**
     * Returns the value under [alias].
     *
     * @throws VolanAggregateNotAskedException when the query did not ask for it.
     */
    @JvmStatic
    public fun require(values: Map<String, Any?>, alias: String, description: String): Any? {
        if (!values.containsKey(alias)) throw VolanAggregateNotAskedException(alias, description)
        return values[alias]
    }

    /** Reads a count, which every database returns as some integral type or other. */
    @JvmStatic
    public fun count(values: Map<String, Any?>, alias: String, description: String): Long =
        (require(values, alias, description) as? Number)?.toLong() ?: 0L

    /** Reads a total, widened so that no database's choice of return type loses precision. */
    @JvmStatic
    public fun decimal(values: Map<String, Any?>, alias: String, description: String): BigDecimal? =
        when (val value = require(values, alias, description)) {
            null -> null
            is BigDecimal -> value
            is Number -> BigDecimal(value.toString())
            else -> throw VolanMappingException("`$alias` came back as ${value.javaClass.name}, which is not a number.")
        }

    /** Reads a mean. */
    @JvmStatic
    public fun double(values: Map<String, Any?>, alias: String, description: String): Double? =
        (require(values, alias, description) as? Number)?.toDouble()

    /** Reads a smallest or largest text value. */
    @JvmStatic
    public fun string(values: Map<String, Any?>, alias: String, description: String): String? =
        require(values, alias, description)?.toString()

    /** Reads a smallest or largest whole number that fits in an `Int`. */
    @JvmStatic
    public fun int(values: Map<String, Any?>, alias: String, description: String): Int? = number(values, alias, description)?.toInt()

    /** Reads a smallest or largest whole number. */
    @JvmStatic
    public fun long(values: Map<String, Any?>, alias: String, description: String): Long? = number(values, alias, description)?.toLong()

    /** Reads a smallest or largest single-precision number. */
    @JvmStatic
    public fun float(values: Map<String, Any?>, alias: String, description: String): Float? = number(values, alias, description)?.toFloat()

    /**
     * Reads a smallest or largest moment in time.
     *
     * Drivers disagree about which type a timestamp comes back as, so every shape they agree to hand
     * out is accepted here rather than in each generated accessor.
     */
    @JvmStatic
    public fun instant(values: Map<String, Any?>, alias: String, description: String): Instant? =
        when (val value = require(values, alias, description)) {
            null -> null
            is Instant -> value
            is Timestamp -> value.toInstant()
            is OffsetDateTime -> value.toInstant()
            is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
            else -> throw unreadable(alias, value, "a moment in time")
        }

    /** Reads a smallest or largest date. */
    @JvmStatic
    public fun localDate(values: Map<String, Any?>, alias: String, description: String): LocalDate? =
        when (val value = require(values, alias, description)) {
            null -> null
            is LocalDate -> value
            is java.sql.Date -> value.toLocalDate()
            else -> throw unreadable(alias, value, "a date")
        }

    /** Reads a smallest or largest time of day. */
    @JvmStatic
    public fun localTime(values: Map<String, Any?>, alias: String, description: String): LocalTime? =
        when (val value = require(values, alias, description)) {
            null -> null
            is LocalTime -> value
            is Time -> value.toLocalTime()
            else -> throw unreadable(alias, value, "a time of day")
        }

    /** Reads a smallest or largest UUID. */
    @JvmStatic
    public fun uuid(values: Map<String, Any?>, alias: String, description: String): UUID? =
        when (val value = require(values, alias, description)) {
            null -> null
            is UUID -> value
            is String -> UUID.fromString(value)
            else -> throw unreadable(alias, value, "a UUID")
        }

    private fun number(values: Map<String, Any?>, alias: String, description: String): Number? =
        when (val value = require(values, alias, description)) {
            null -> null
            is Number -> value
            else -> throw unreadable(alias, value, "a number")
        }

    private fun unreadable(alias: String, value: Any, expected: String): VolanMappingException =
        VolanMappingException("`$alias` came back as ${value.javaClass.name}, which Volan cannot read as $expected.")
}

/**
 * Thrown when code reads a summary the query did not ask for.
 *
 * @property alias the summary that was read.
 */
public class VolanAggregateNotAskedException internal constructor(public val alias: String, description: String) :
    VolanException(
        "this query did not work out $description.\n" +
            "  Ask for it in the `aggregate` block before reading it.",
    )
