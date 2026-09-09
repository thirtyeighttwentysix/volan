package io.github.thirtyeighttwentysix.volan.runtime

import org.jspecify.annotations.NullMarked
import org.jspecify.annotations.Nullable

/**
 * How two values are compared.
 *
 * The operator is part of the query description, never of a SQL string: a dialect turns it into
 * whatever that database writes, and the value beside it is always bound as a parameter.
 */
@NullMarked
public enum class ComparisonOperator {
    /** Equal. */
    EQUAL,

    /** Not equal. */
    NOT_EQUAL,

    /** Less than. */
    LESS_THAN,

    /** Less than or equal. */
    LESS_THAN_OR_EQUAL,

    /** Greater than. */
    GREATER_THAN,

    /** Greater than or equal. */
    GREATER_THAN_OR_EQUAL,

    /** Contains the given text. */
    CONTAINS,

    /** Starts with the given text. */
    STARTS_WITH,

    /** Ends with the given text. */
    ENDS_WITH,
}

/** Whether text comparisons distinguish upper and lower case. */
@NullMarked
public enum class TextMatchMode {
    /** Compare exactly as stored, which is what the database does by default. */
    SENSITIVE,

    /** Ignore case. */
    INSENSITIVE,
}

/** How many rows on the far side of a relation have to match for the row on this side to match. */
@NullMarked
public enum class RelationQuantifier {
    /** At least one related row matches. */
    SOME,

    /** Every related row matches. */
    EVERY,

    /** No related row matches. */
    NONE,

    /** The single related row matches. */
    IS,

    /** The single related row does not match. */
    IS_NOT,
}

/**
 * A condition on the rows a query returns.
 *
 * This is a description, not SQL. Values sit in the tree as values and reach the database as
 * statement parameters, which is why no query Volan builds can be injected into.
 */
@NullMarked
public sealed interface Filter {
    /** Compares a column with a value. */
    @NullMarked
    public data class Compare @JvmOverloads constructor(
        public val column: String,
        public val operator: ComparisonOperator,
        public val value: @Nullable Any?,
        public val mode: TextMatchMode = TextMatchMode.SENSITIVE,
    ) : Filter

    /** Matches when a column's value lies between two bounds, both included. */
    @NullMarked
    public data class Between(
        public val column: String,
        public val lower: @Nullable Any?,
        public val upper: @Nullable Any?,
    ) : Filter

    /** Matches when a column's value is one of [values], or none of them when [negated]. */
    @NullMarked
    public data class InList @JvmOverloads constructor(
        public val column: String,
        public val values: List<@Nullable Any?>,
        public val negated: Boolean = false,
    ) : Filter

    /** Matches when a column is null, or is not null when [negated]. */
    @NullMarked
    public data class IsNull @JvmOverloads constructor(public val column: String, public val negated: Boolean = false) : Filter

    /** Matches when every one of [filters] matches. An empty list matches every row. */
    @NullMarked
    public data class And(public val filters: List<Filter>) : Filter

    /** Matches when at least one of [filters] matches. An empty list matches no row. */
    @NullMarked
    public data class Or(public val filters: List<Filter>) : Filter

    /** Matches when [filter] does not. */
    @NullMarked
    public data class Not(public val filter: Filter) : Filter

    /**
     * Matches on the rows at the far end of a relation.
     *
     * @property relation the relation field on the model being queried.
     * @property quantifier how many related rows have to match.
     * @property filter the condition applied to them; `null` means "any row at all".
     */
    @NullMarked
    public data class Related(
        public val relation: String,
        public val quantifier: RelationQuantifier,
        public val filter: @Nullable Filter?,
    ) : Filter

    public companion object {
        /**
         * Combines [filters] into one condition: nothing when the list is empty, the filter itself
         * when there is one, and an [And] otherwise.
         *
         * Generated DSLs call this after collecting the conditions a `where { … }` block wrote, so
         * that a single condition does not end up wrapped in a pointless `AND`.
         */
        @JvmStatic
        public fun all(filters: List<Filter>): @Nullable Filter? = when (filters.size) {
            0 -> null
            1 -> filters[0]
            else -> And(filters)
        }

        /** Combines [filters] with `OR`, collapsing the trivial cases the same way as [all]. */
        @JvmStatic
        public fun any(filters: List<Filter>): @Nullable Filter? = when (filters.size) {
            0 -> null
            1 -> filters[0]
            else -> Or(filters)
        }
    }
}
