package io.github.thirtyeighttwentysix.volan.runtime

/**
 * The receiver a generated `orderBy { … }` block runs against.
 *
 * Terms apply in the order they are written, so `orderBy { role.asc(); createdAt.desc() }` sorts by
 * role first and breaks ties by date.
 */
public abstract class OrderScope protected constructor() {
    internal val terms: MutableList<OrderTerm> = ArrayList()

    /** Creates a handle for sorting on a column. */
    protected fun orderField(column: String): OrderField = OrderField(column, this)

    /** The terms this scope collected, in the order they were written. */
    public fun build(): List<OrderTerm> = terms.toList()
}

/** A column being sorted on. */
public class OrderField internal constructor(private val column: String, private val scope: OrderScope) {
    /** Sorts smallest first. */
    public fun asc(): Unit = add(SortDirection.ASCENDING, NullsOrder.DEFAULT)

    /** Sorts largest first. */
    public fun desc(): Unit = add(SortDirection.DESCENDING, NullsOrder.DEFAULT)

    /** Sorts smallest first, with nulls before every value. */
    public fun ascNullsFirst(): Unit = add(SortDirection.ASCENDING, NullsOrder.FIRST)

    /** Sorts smallest first, with nulls after every value. */
    public fun ascNullsLast(): Unit = add(SortDirection.ASCENDING, NullsOrder.LAST)

    /** Sorts largest first, with nulls before every value. */
    public fun descNullsFirst(): Unit = add(SortDirection.DESCENDING, NullsOrder.FIRST)

    /** Sorts largest first, with nulls after every value. */
    public fun descNullsLast(): Unit = add(SortDirection.DESCENDING, NullsOrder.LAST)

    private fun add(direction: SortDirection, nulls: NullsOrder) {
        scope.terms.add(OrderTerm(column, direction, nulls))
    }
}

/**
 * The receiver a generated `select { … }` block runs against.
 *
 * Naming a field marks it for reading; everything not named is left out of the query and out of the
 * projection it produces.
 */
public abstract class SelectScope protected constructor() {
    internal val fields: MutableSet<String> = LinkedHashSet()

    /** Marks [field] as selected. */
    protected fun markSelected(field: String) {
        fields.add(field)
    }

    /** The fields this scope collected, in the order they were named. */
    public fun build(): Set<String> = LinkedHashSet(fields)
}

/**
 * The receiver a generated `include { … }` block runs against.
 *
 * Each relation named here becomes one extra statement at execution time, never one per row.
 */
public abstract class IncludeScope protected constructor() {
    internal val requests: MutableList<RelationRequest> = ArrayList()

    /** Requests [relation], fetched as [spec] describes. */
    protected fun includeRelation(relation: String, spec: QuerySpec) {
        requests.add(RelationRequest(relation, spec))
    }

    /** The relations this scope collected, in the order they were named. */
    public fun build(): List<RelationRequest> = requests.toList()
}

/**
 * The receiver a generated read block runs against: the filter, the ordering, the paging, the
 * projection and the relations to load, in one place.
 *
 * Generated subclasses add the typed `where`, `orderBy`, `select` and `include` entry points; the
 * plumbing that turns what they collect into a [QuerySpec] lives here.
 *
 * @param model the model being read.
 */
public abstract class QueryScope protected constructor(private val model: String) {
    /** How many rows to return at most. Null returns all of them. */
    public var take: Int? = null

    /** How many rows to pass over before returning any. */
    public var skip: Int? = null

    private var distinctColumns: List<String> = emptyList()
    private var filter: Filter? = null
    private var orderTerms: List<OrderTerm> = emptyList()
    private var selectedColumns: List<String>? = null
    private var relations: List<RelationRequest> = emptyList()
    private var cursor: Map<String, Any?>? = null
    private var skipCursorRow: Boolean = true

    /** Records the condition a `where { … }` block collected. */
    protected fun recordFilter(scope: FilterScope) {
        filter = Filter.all(listOfNotNull(filter, scope.build()))
    }

    /** Records the terms an `orderBy { … }` block collected. */
    protected fun recordOrder(scope: OrderScope) {
        orderTerms = orderTerms + scope.build()
    }

    /** Records the fields a `select { … }` block collected. */
    protected fun recordSelection(columns: List<String>) {
        selectedColumns = columns
    }

    /** Records the columns a `distinct { … }` block collected. */
    protected fun recordDistinct(columns: List<String>) {
        distinctColumns = columns
    }

    /** Records the relations an `include { … }` block collected. */
    protected fun recordIncludes(scope: IncludeScope) {
        relations = relations + scope.build()
    }

    /**
     * Resumes from the row identified by [key], excluding that row itself unless [inclusive].
     *
     * Cursor paging stays correct while rows are being inserted, which offset paging cannot promise.
     */
    protected fun recordCursor(key: Map<String, Any?>, inclusive: Boolean) {
        cursor = key
        skipCursorRow = !inclusive
    }

    /** Everything this scope collected, as a query description. */
    public fun build(): QuerySpec = QuerySpec(
        model = model,
        filter = filter,
        orderBy = orderTerms,
        pagination = Pagination(take, skip, cursor, skipCursorRow),
        distinct = distinctColumns,
        columns = selectedColumns,
        includes = relations,
    )
}
