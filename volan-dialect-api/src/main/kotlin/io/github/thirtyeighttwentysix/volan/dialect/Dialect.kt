package io.github.thirtyeighttwentysix.volan.dialect

/**
 * What a database can and cannot do.
 *
 * The planner reads these instead of asking "which database is this", so that adding a backend is a
 * matter of stating its abilities rather than of finding every place that assumed PostgreSQL.
 *
 * @property returningClause whether a write can read back the rows it changed in the same statement.
 * @property insertOnConflict whether an insert can update on conflict, giving upsert in one round trip.
 * @property caseInsensitiveLike whether the database has a case-insensitive `LIKE` of its own.
 * @property distinctOn whether `DISTINCT ON` is available, rather than de-duplicating on every column.
 * @property nullsOrdering whether `NULLS FIRST` and `NULLS LAST` can be written.
 * @property tupleComparison whether `(a, b) > (?, ?)` is understood, which composite cursors need.
 * @property arrayColumns whether a column can hold an array.
 * @property maximumParameters how many placeholders one statement may carry.
 */
public data class DialectCapabilities(
    public val returningClause: Boolean,
    public val insertOnConflict: Boolean,
    public val caseInsensitiveLike: Boolean,
    public val distinctOn: Boolean,
    public val nullsOrdering: Boolean,
    public val tupleComparison: Boolean,
    public val arrayColumns: Boolean,
    public val maximumParameters: Int,
)

/**
 * Turns Volan's SQL model into the text one database understands.
 *
 * A dialect is the only place in Volan that knows SQL syntax. Everything above it works in terms of
 * the model in this module, which is why a query can be built, inspected and tested without a database
 * anywhere near it.
 */
public interface Dialect {
    /** The `provider` value in a schema that selects this dialect. */
    public val id: String

    /** What this database can do. */
    public val capabilities: DialectCapabilities

    /** Renders a read. */
    public fun render(select: SqlSelect): SqlStatement

    /** Renders an insert. */
    public fun render(insert: SqlInsert): SqlStatement

    /** Renders an update. */
    public fun render(update: SqlUpdate): SqlStatement

    /** Renders a delete. */
    public fun render(delete: SqlDelete): SqlStatement

    /** Quotes [name] so that a column called `order` or `select` is still a column. */
    public fun quote(name: String): String
}
