package io.github.thirtyeighttwentysix.volan.runtime

/**
 * A read that folds rows into groups and summarises each one.
 *
 * @property model the model being grouped.
 * @property by the fields whose values define a group, in the order they were named.
 * @property filter which rows go into the groups at all; `null` for every row.
 * @property aggregations what to work out for each group, each under its own alias.
 * @property having which groups survive, written over the summaries rather than over the rows.
 * @property havingAggregations every summary [having] can name, so that a condition on one can be
 *   turned back into the expression it stands for. A database cannot be asked about a summary by the
 *   name it comes back under, so the planner has to put the expression back in its place.
 * @property orderBy the sort terms, over the grouped fields.
 * @property pagination how many groups to return and from where.
 */
public data class GroupSpec(
    public val model: String,
    public val by: List<String>,
    public val filter: Filter? = null,
    public val aggregations: List<Aggregation> = emptyList(),
    public val having: Filter? = null,
    public val havingAggregations: Map<String, Aggregation> = emptyMap(),
    public val orderBy: List<OrderTerm> = emptyList(),
    public val pagination: Pagination = Pagination.NONE,
)

/**
 * One group: the values that define it, and what was worked out about it.
 *
 * @property key the grouped fields, read the same way a partial `select` is read.
 * @property values the summaries, keyed by the alias each was asked for under.
 */
public data class GroupRow<K>(public val key: K, public val values: Map<String, Any?>)

/**
 * The receiver a generated `having { … }` block runs against.
 *
 * Conditions here are written over summaries rather than over columns, so each handle is bound to the
 * alias its summary comes back under and remembers which summary that is.
 *
 * Only summaries are on offer. A condition on a grouped field is the same condition on the rows that
 * went into the group, which is what `where` is for — and `where` narrows before the grouping work is
 * done rather than after it.
 */
public abstract class HavingScope protected constructor() : FilterScope() {
    private val known = LinkedHashMap<String, Aggregation>()

    /** Creates a handle for comparing the summary [function] works out over [column]. */
    protected fun <T : Comparable<T>> aggregateField(function: AggregateFunction, column: String?, alias: String): OrderedFilterField<T> {
        known[alias] = Aggregation(function, column, alias)
        return orderedField(alias)
    }

    /** Every summary a condition in this scope can name. */
    public fun aggregations(): Map<String, Aggregation> = LinkedHashMap(known)
}

/**
 * The receiver a generated `groupBy { … }` block runs against.
 *
 * Generated subclasses add the typed `by`, `where`, `having` and summary entry points; collecting what
 * they ask for lives here.
 *
 * @param model the model being grouped.
 */
public abstract class GroupScope protected constructor(private val model: String) {
    /** How many groups to return at most. Null returns all of them. */
    public var take: Int? = null

    /** How many groups to pass over before returning any. */
    public var skip: Int? = null

    private val aggregations = ArrayList<Aggregation>()
    private var by: List<String> = emptyList()
    private var filter: Filter? = null
    private var having: Filter? = null
    private var havingAggregations: Map<String, Aggregation> = emptyMap()
    private var orderTerms: List<OrderTerm> = emptyList()

    /** Records the fields a `by { … }` block collected. */
    protected fun recordGrouping(fields: Set<String>) {
        by = by + fields.filterNot { by.contains(it) }
    }

    /** Records the condition a `where { … }` block collected. */
    protected fun recordFilter(scope: FilterScope) {
        filter = Filter.all(listOfNotNull(filter, scope.build()))
    }

    /** Records the condition a `having { … }` block collected. */
    protected fun recordHaving(scope: HavingScope) {
        having = Filter.all(listOfNotNull(having, scope.build()))
        havingAggregations = havingAggregations + scope.aggregations()
    }

    /** Records the terms an `orderBy { … }` block collected. */
    protected fun recordOrder(scope: OrderScope) {
        orderTerms = orderTerms + scope.build()
    }

    /** Records one summary to work out for each group. */
    protected fun record(function: AggregateFunction, column: String?, alias: String) {
        aggregations.removeAll { it.alias == alias }
        aggregations.add(Aggregation(function, column, alias))
    }

    /** The fields this scope has been told to group by, as the result will read them. */
    public fun groupedFields(): Set<String> = LinkedHashSet(by)

    /** Everything this scope collected, as a description. */
    public fun build(): GroupSpec = GroupSpec(
        model = model,
        by = by.toList(),
        filter = filter,
        aggregations = aggregations.toList(),
        having = having,
        havingAggregations = havingAggregations,
        orderBy = orderTerms,
        pagination = Pagination(take, skip),
    )
}
