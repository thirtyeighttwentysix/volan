package io.github.thirtyeighttwentysix.volan.runtime

/** Which way a sort runs. */
public enum class SortDirection {
    /** Smallest first. */
    ASCENDING,

    /** Largest first. */
    DESCENDING,
}

/** Where nulls go in a sort. */
public enum class NullsOrder {
    /** Wherever the database puts them by default. */
    DEFAULT,

    /** Before every non-null value. */
    FIRST,

    /** After every non-null value. */
    LAST,
}

/**
 * One term of an `ORDER BY`.
 *
 * @property column the column to sort on.
 * @property direction which way the sort runs.
 * @property nulls where nulls go.
 */
public data class OrderTerm(
    public val column: String,
    public val direction: SortDirection,
    public val nulls: NullsOrder = NullsOrder.DEFAULT,
)

/**
 * How much of a result to return and where to start.
 *
 * Offset paging ([skip]) and cursor paging ([cursor]) are both supported. A cursor names the row to
 * resume after by its key columns, which keeps paging stable while rows are being inserted — something
 * `OFFSET` cannot do.
 *
 * @property take how many rows to return at most; `null` for all of them.
 * @property skip how many rows to pass over first.
 * @property cursor the key of the row to resume from, column name to value.
 * @property skipCursorRow whether the cursor row itself is excluded.
 */
public data class Pagination(
    public val take: Int? = null,
    public val skip: Int? = null,
    public val cursor: Map<String, Any?>? = null,
    public val skipCursorRow: Boolean = true,
) {
    public companion object {
        /** No limit, no offset, no cursor. */
        @JvmField
        public val NONE: Pagination = Pagination()
    }
}

/**
 * A relation to load alongside the rows being queried, and how to load it.
 *
 * @property relation the relation field on the model being queried.
 * @property spec what to fetch on the far side, including its own filter, ordering, paging and
 *   further relations.
 */
public data class RelationRequest(public val relation: String, public val spec: QuerySpec)

/**
 * Everything a read is asking for, described without a word of SQL.
 *
 * A dialect renders this at execution time; until then it is a value that can be inspected, logged,
 * compared in a test and cached by shape.
 *
 * @property model the model being read.
 * @property filter the condition rows must satisfy; `null` for every row.
 * @property orderBy the sort terms, in priority order.
 * @property pagination how much to return and from where.
 * @property distinct the columns rows must differ in; empty for no de-duplication.
 * @property columns the columns to read; `null` means every column of the model.
 * @property includes the relations to load with the result.
 */
public data class QuerySpec(
    public val model: String,
    public val filter: Filter? = null,
    public val orderBy: List<OrderTerm> = emptyList(),
    public val pagination: Pagination = Pagination.NONE,
    public val distinct: List<String> = emptyList(),
    public val columns: List<String>? = null,
    public val includes: List<RelationRequest> = emptyList(),
)

/**
 * A row to insert.
 *
 * @property model the model being written.
 * @property values column name to value, for the columns the caller set.
 * @property nested the rows to write alongside it on the other side of a relation. They are applied in
 *   one transaction with this row, so either the whole shape lands or none of it does.
 */
public data class CreateSpec(
    public val model: String,
    public val values: Map<String, Any?>,
    public val nested: List<NestedWrite> = emptyList(),
)

/**
 * Something to do to the rows on the far side of a relation while writing this one.
 *
 * Which of these can be expressed is decided by the generated DSL; the runtime's job is to apply them
 * in an order that leaves no row pointing at something that does not exist yet.
 */
public sealed interface NestedWrite {
    /** The relation field on the row being written. */
    public val relation: String

    /** Rows to insert on the far side. */
    public data class CreateRows(override val relation: String, public val rows: List<CreateSpec>) : NestedWrite

    /** Existing rows to attach, each identified by a filter that must select exactly one. */
    public data class ConnectRows(override val relation: String, public val filters: List<Filter>) : NestedWrite

    /** Rows to attach if they exist and to insert if they do not. */
    public data class ConnectOrCreateRows(override val relation: String, public val entries: List<ConnectOrCreateEntry>) : NestedWrite
}

/**
 * One "attach it or write it" of a nested write.
 *
 * @property filter what to look for.
 * @property row what to insert when nothing matches.
 */
public data class ConnectOrCreateEntry(public val filter: Filter, public val row: CreateSpec)

/**
 * A change to apply to the rows a filter selects.
 *
 * @property model the model being written.
 * @property filter which rows to change; `null` for every row.
 * @property values column name to new value.
 */
public data class UpdateSpec(
    public val model: String,
    public val filter: Filter?,
    public val values: Map<String, Any?>,
)

/**
 * A deletion.
 *
 * @property model the model being written.
 * @property filter which rows to delete; `null` for every row.
 */
public data class DeleteSpec(public val model: String, public val filter: Filter?)

/**
 * An insert-or-update: write [update] to the row [filter] selects, or insert [create] when it selects
 * none.
 *
 * The two payloads are separate because they usually differ — an insert has to supply everything the
 * row needs, while an update touches only what is changing.
 *
 * @property model the model being written.
 * @property filter which row to look for; it must select at most one.
 * @property create the values to insert when no row matches.
 * @property update the values to write when one does.
 */
public data class UpsertSpec(
    public val model: String,
    public val filter: Filter?,
    public val create: Map<String, Any?>,
    public val update: Map<String, Any?>,
)
