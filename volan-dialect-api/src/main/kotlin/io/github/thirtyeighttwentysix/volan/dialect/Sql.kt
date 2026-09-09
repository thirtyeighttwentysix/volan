package io.github.thirtyeighttwentysix.volan.dialect

import org.jspecify.annotations.NullMarked
import org.jspecify.annotations.Nullable

/**
 * A statement ready to be prepared, and the values to bind to it.
 *
 * The text never contains a user value. Everything that came from the caller is in [parameters], in
 * the order the placeholders appear, which is what makes a Volan query impossible to inject into.
 *
 * @property sql the statement text, with `?` placeholders.
 * @property parameters the values to bind, in placeholder order.
 */
@NullMarked
public data class SqlStatement(public val sql: String, public val parameters: List<@Nullable Any?>) {
    override fun toString(): String = sql
}

/** Something that can appear where SQL expects a value. */
@NullMarked
public sealed interface SqlExpression {
    /** A column, optionally qualified by a table alias. */
    @NullMarked
    public data class Column(public val table: @Nullable String?, public val name: String) : SqlExpression

    /** A value bound as a statement parameter. */
    @NullMarked
    public data class Parameter(public val value: @Nullable Any?) : SqlExpression

    /** A fragment produced by Volan itself, never by a caller: `DEFAULT`, `CURRENT_TIMESTAMP`, and the like. */
    @NullMarked
    public data class Keyword(public val text: String) : SqlExpression

    /** A call such as `LOWER(x)`. */
    @NullMarked
    public data class Call(public val name: String, public val arguments: List<SqlExpression>) : SqlExpression
}

/** How two values are compared in SQL. */
@NullMarked
public enum class SqlComparison(public val symbol: String) {
    /** `=` */
    EQUAL("="),

    /** `<>` */
    NOT_EQUAL("<>"),

    /** `<` */
    LESS_THAN("<"),

    /** `<=` */
    LESS_THAN_OR_EQUAL("<="),

    /** `>` */
    GREATER_THAN(">"),

    /** `>=` */
    GREATER_THAN_OR_EQUAL(">="),
}

/** How a `LIKE` pattern is anchored. */
@NullMarked
public enum class SqlTextMatch {
    /** The value appears anywhere. */
    CONTAINS,

    /** The value appears at the start. */
    STARTS_WITH,

    /** The value appears at the end. */
    ENDS_WITH,
}

/** A condition in a `WHERE` clause. */
@NullMarked
public sealed interface SqlCondition {
    /** Compares two expressions. */
    @NullMarked
    public data class Compare @JvmOverloads constructor(
        public val left: SqlExpression,
        public val operator: SqlComparison,
        public val right: SqlExpression,
        public val caseInsensitive: Boolean = false,
    ) : SqlCondition

    /**
     * Matches text by prefix, suffix or substring.
     *
     * The value is bound as a parameter with its wildcards escaped, so a search for `100%` looks for
     * the text `100%` rather than for everything starting with `100`.
     */
    @NullMarked
    public data class TextMatch @JvmOverloads constructor(
        public val column: SqlExpression,
        public val match: SqlTextMatch,
        public val value: String,
        public val caseInsensitive: Boolean = false,
    ) : SqlCondition

    /** Matches a range, both ends included. */
    @NullMarked
    public data class Between(
        public val column: SqlExpression,
        public val lower: SqlExpression,
        public val upper: SqlExpression,
    ) : SqlCondition

    /** Matches membership in a list. An empty list matches nothing, or everything when [negated]. */
    @NullMarked
    public data class InList @JvmOverloads constructor(
        public val column: SqlExpression,
        public val values: List<SqlExpression>,
        public val negated: Boolean = false,
    ) : SqlCondition

    /** Matches null, or not null when [negated]. */
    @NullMarked
    public data class IsNull @JvmOverloads constructor(public val column: SqlExpression, public val negated: Boolean = false) : SqlCondition

    /** Every condition has to hold. */
    @NullMarked
    public data class And(public val conditions: List<SqlCondition>) : SqlCondition

    /** At least one condition has to hold. */
    @NullMarked
    public data class Or(public val conditions: List<SqlCondition>) : SqlCondition

    /** The condition must not hold. */
    @NullMarked
    public data class Not(public val condition: SqlCondition) : SqlCondition

    /** A correlated subquery has to return a row, or none when [negated]. */
    @NullMarked
    public data class Exists @JvmOverloads constructor(public val subquery: SqlSelect, public val negated: Boolean = false) : SqlCondition

    /**
     * Compares a tuple of columns with a tuple of values, as cursor paging over a composite key needs.
     *
     * `(a, b) > (?, ?)` is not the same as `a > ? AND b > ?`, and getting it wrong silently skips rows.
     */
    @NullMarked
    public data class CompareTuple(
        public val columns: List<SqlExpression>,
        public val operator: SqlComparison,
        public val values: List<SqlExpression>,
    ) : SqlCondition
}

/** Where nulls go in an `ORDER BY`. */
@NullMarked
public enum class SqlNulls {
    /** Leave it to the database. */
    DEFAULT,

    /** Before every value. */
    FIRST,

    /** After every value. */
    LAST,
}

/**
 * One term of an `ORDER BY`.
 *
 * @property expression what to sort on.
 * @property descending whether the sort runs largest first.
 * @property nulls where nulls go.
 */
@NullMarked
public data class SqlOrder @JvmOverloads constructor(
    public val expression: SqlExpression,
    public val descending: Boolean,
    public val nulls: SqlNulls = SqlNulls.DEFAULT,
)

/** Something a `SELECT` returns. */
@NullMarked
public sealed interface SqlSelectItem {
    /** A column, under an optional alias. */
    @NullMarked
    public data class Column(public val expression: SqlExpression, public val alias: @Nullable String?) : SqlSelectItem

    /** `COUNT(*)`, under an alias. */
    @NullMarked
    public data class CountAll(public val alias: String) : SqlSelectItem

    /** An aggregate over a column, under an alias. */
    @NullMarked
    public data class Aggregate(
        public val function: SqlAggregate,
        public val expression: SqlExpression,
        public val alias: String,
    ) : SqlSelectItem
}

/** The aggregates Volan can ask for. */
@NullMarked
public enum class SqlAggregate(public val function: String) {
    /** Number of non-null values. */
    COUNT("COUNT"),

    /** Sum. */
    SUM("SUM"),

    /** Mean. */
    AVERAGE("AVG"),

    /** Smallest value. */
    MINIMUM("MIN"),

    /** Largest value. */
    MAXIMUM("MAX"),
}

/**
 * A read.
 *
 * @property table the table to read from.
 * @property alias the name the table is known by inside this statement.
 * @property items what to return.
 * @property condition the `WHERE` clause.
 * @property groupBy the columns to group by.
 * @property having the condition applied after grouping.
 * @property orderBy the sort terms, in priority order.
 * @property limit how many rows to return at most.
 * @property offset how many rows to skip.
 * @property distinctOn the columns rows must differ in; empty for no de-duplication.
 */
@NullMarked
public data class SqlSelect @JvmOverloads constructor(
    public val table: String,
    public val alias: @Nullable String? = null,
    public val items: List<SqlSelectItem> = emptyList(),
    public val condition: @Nullable SqlCondition? = null,
    public val groupBy: List<SqlExpression> = emptyList(),
    public val having: @Nullable SqlCondition? = null,
    public val orderBy: List<SqlOrder> = emptyList(),
    public val limit: @Nullable Int? = null,
    public val offset: @Nullable Int? = null,
    public val distinctOn: List<SqlExpression> = emptyList(),
)

/**
 * An insert of one or more rows.
 *
 * @property table the table to write to.
 * @property columns the columns each row supplies, in order.
 * @property rows the values, one list per row, matching [columns].
 * @property returning the columns to read back; empty to return nothing.
 */
@NullMarked
public data class SqlInsert @JvmOverloads constructor(
    public val table: String,
    public val columns: List<String>,
    public val rows: List<List<SqlExpression>>,
    public val returning: List<String> = emptyList(),
)

/**
 * An update.
 *
 * @property table the table to write to.
 * @property assignments the columns to change and what to set them to.
 * @property condition which rows to change; `null` for every row.
 * @property returning the columns to read back; empty to return nothing.
 */
@NullMarked
public data class SqlUpdate @JvmOverloads constructor(
    public val table: String,
    public val assignments: List<SqlAssignment>,
    public val condition: @Nullable SqlCondition?,
    public val returning: List<String> = emptyList(),
)

/**
 * One `column = value` of an update.
 *
 * @property column the column to change.
 * @property value what to set it to.
 */
@NullMarked
public data class SqlAssignment(public val column: String, public val value: SqlExpression)

/**
 * A delete.
 *
 * @property table the table to delete from.
 * @property condition which rows to delete; `null` for every row.
 * @property returning the columns to read back; empty to return nothing.
 */
@NullMarked
public data class SqlDelete @JvmOverloads constructor(
    public val table: String,
    public val condition: @Nullable SqlCondition?,
    public val returning: List<String> = emptyList(),
)
