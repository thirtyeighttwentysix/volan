package io.github.thirtyeighttwentysix.volan.runtime

import io.github.thirtyeighttwentysix.volan.dialect.Dialect
import io.github.thirtyeighttwentysix.volan.dialect.SqlAggregate
import io.github.thirtyeighttwentysix.volan.dialect.SqlAssignment
import io.github.thirtyeighttwentysix.volan.dialect.SqlComparison
import io.github.thirtyeighttwentysix.volan.dialect.SqlCondition
import io.github.thirtyeighttwentysix.volan.dialect.SqlDelete
import io.github.thirtyeighttwentysix.volan.dialect.SqlExpression
import io.github.thirtyeighttwentysix.volan.dialect.SqlInsert
import io.github.thirtyeighttwentysix.volan.dialect.SqlNulls
import io.github.thirtyeighttwentysix.volan.dialect.SqlOrder
import io.github.thirtyeighttwentysix.volan.dialect.SqlSelect
import io.github.thirtyeighttwentysix.volan.dialect.SqlSelectItem
import io.github.thirtyeighttwentysix.volan.dialect.SqlTextMatch
import io.github.thirtyeighttwentysix.volan.dialect.SqlUpdate
import java.time.Instant

/**
 * Turns a query description into the SQL model a dialect renders.
 *
 * This is where schema names become database names, where a filter on a relation becomes a correlated
 * subquery, and where `@updatedAt` gets its value. Nothing here produces text: that stays the
 * dialect's job, so a plan can be inspected and compared without a database.
 */
internal class QueryPlanner(private val registry: TableRegistry, private val dialect: Dialect) {
    fun select(spec: QuerySpec): SqlSelect {
        val table = registry.require(spec.model)
        val scope = Scope(table, Aliases())
        val columns = spec.columns ?: table.columns.map { it.column }
        val ordering = orderBy(spec.orderBy, scope)
        return SqlSelect(
            table = table.table,
            alias = scope.alias,
            items = columns.map { SqlSelectItem.Column(scope.column(it), alias = null) },
            condition = allOf(listOfNotNull(spec.filter?.let { condition(it, scope) }, cursorCondition(spec, scope, ordering))),
            orderBy = ordering.ifEmpty { cursorOrdering(spec, scope) },
            limit = spec.pagination.take,
            offset = spec.pagination.skip,
            distinctOn = spec.distinct.map { scope.column(it) },
        )
    }

    fun count(spec: QuerySpec): SqlSelect = select(spec).copy(
        items = listOf(SqlSelectItem.CountAll(COUNT_ALIAS)),
        orderBy = emptyList(),
    )

    /** Turns a request for summaries into one `SELECT` of aggregate expressions. */
    fun aggregate(spec: AggregateSpec): SqlSelect {
        val table = registry.require(spec.model)
        val scope = Scope(table, Aliases())
        return SqlSelect(
            table = table.table,
            alias = scope.alias,
            items = spec.aggregations.map { item ->
                val column = item.column
                if (column == null) {
                    SqlSelectItem.CountAll(item.alias)
                } else {
                    SqlSelectItem.Aggregate(sqlFunction(item.function), scope.column(column), item.alias)
                }
            },
            condition = spec.filter?.let { condition(it, scope) },
        )
    }

    private fun sqlFunction(function: AggregateFunction): SqlAggregate = when (function) {
        AggregateFunction.COUNT -> SqlAggregate.COUNT
        AggregateFunction.SUM -> SqlAggregate.SUM
        AggregateFunction.AVERAGE -> SqlAggregate.AVERAGE
        AggregateFunction.MINIMUM -> SqlAggregate.MINIMUM
        AggregateFunction.MAXIMUM -> SqlAggregate.MAXIMUM
    }

    fun exists(spec: QuerySpec): SqlSelect = select(spec).copy(
        items = listOf(SqlSelectItem.Column(SqlExpression.Keyword("1"), alias = null)),
        orderBy = emptyList(),
        limit = 1,
    )

    /**
     * Reads the pairs of a many-to-many join table for the given local keys.
     *
     * A join table is not a model — nothing generates a client for it — so this is built here rather
     * than described as a [QuerySpec].
     */
    fun joinSelect(table: String, localColumns: List<String>, targetColumns: List<String>, keys: List<List<Any?>>): SqlSelect {
        val columns = (localColumns + targetColumns).map { SqlSelectItem.Column(SqlExpression.Column(null, it), alias = null) }
        val condition = if (localColumns.size == 1) {
            SqlCondition.InList(SqlExpression.Column(null, localColumns.single()), keys.map { SqlExpression.Parameter(it.single()) })
        } else {
            SqlCondition.Or(
                keys.map { key ->
                    SqlCondition.And(
                        localColumns.zip(key).map { (column, value) ->
                            SqlCondition.Compare(
                                SqlExpression.Column(null, column),
                                SqlComparison.EQUAL,
                                SqlExpression.Parameter(value),
                            )
                        },
                    )
                },
            )
        }
        return SqlSelect(table = table, items = columns, condition = condition)
    }

    /** Records pairs in a many-to-many join table. */
    fun joinInsert(
        table: String,
        localColumns: List<String>,
        targetColumns: List<String>,
        local: List<Any?>,
        targets: List<List<Any?>>,
    ): SqlInsert = SqlInsert(
        table = table,
        columns = localColumns + targetColumns,
        rows = targets.map { target -> (local + target).map { SqlExpression.Parameter(it) } },
    )

    fun insert(specs: List<CreateSpec>, now: Instant): SqlInsert {
        require(specs.isNotEmpty()) { "an insert needs at least one row" }
        val table = registry.require(specs.first().model)
        specs.forEach { requireComplete(table, it) }
        val managed = table.columns.filter { it.isUpdatedAt }.map { it.column }
        val columns = (specs.flatMap { it.values.keys }.distinct() + managed).distinct()
        val rows = specs.map { spec ->
            columns.map { column ->
                when {
                    column in managed -> SqlExpression.Parameter(now)
                    spec.values.containsKey(column) -> SqlExpression.Parameter(spec.values[column])
                    else -> SqlExpression.Keyword("DEFAULT")
                }
            }
        }
        return SqlInsert(table.table, columns, rows, returning = table.columns.map { it.column })
    }

    /**
     * Refuses a row that is missing something the database will insist on.
     *
     * This is checked here, at the last moment before the statement is built, rather than where the
     * caller wrote the block: a foreign key supplied by a nested write is absent there and present by
     * now, and complaining early would make writing a row together with its parent impossible.
     */
    private fun requireComplete(table: TableMetadata, spec: CreateSpec) {
        val missing = table.columns.filter { column ->
            !column.isNullable && !column.isGenerated && !column.isUpdatedAt && !spec.values.containsKey(column.column)
        }
        if (missing.isEmpty()) return
        val names = missing.joinToString(", ") { "`${table.model}.${it.field}`" }
        throw VolanValidationException(
            "$names ${if (missing.size == 1) "is" else "are"} required and ${if (missing.size == 1) "was" else "were"} not set.",
        )
    }

    fun update(spec: UpdateSpec, now: Instant, returning: Boolean): SqlUpdate {
        val table = registry.require(spec.model)
        val scope = Scope(table, Aliases(), alias = table.table)
        val assignments = spec.values.map { (column, value) -> SqlAssignment(column, SqlExpression.Parameter(value)) } +
            table.columns.filter { it.isUpdatedAt }.map { SqlAssignment(it.column, SqlExpression.Parameter(now)) }
        return SqlUpdate(
            table = table.table,
            assignments = assignments,
            condition = spec.filter?.let { condition(it, scope) },
            returning = if (returning) table.columns.map { it.column } else emptyList(),
        )
    }

    fun delete(spec: DeleteSpec, returning: Boolean): SqlDelete {
        val table = registry.require(spec.model)
        val scope = Scope(table, Aliases(), alias = table.table)
        return SqlDelete(
            table = table.table,
            condition = spec.filter?.let { condition(it, scope) },
            returning = if (returning) table.columns.map { it.column } else emptyList(),
        )
    }

    private fun condition(filter: Filter, scope: Scope): SqlCondition = when (filter) {
        is Filter.Compare -> compare(filter, scope)
        is Filter.Between -> SqlCondition.Between(
            scope.column(filter.column),
            SqlExpression.Parameter(filter.lower),
            SqlExpression.Parameter(filter.upper),
        )
        is Filter.InList -> SqlCondition.InList(
            scope.column(filter.column),
            filter.values.map { SqlExpression.Parameter(it) },
            filter.negated,
        )
        is Filter.IsNull -> SqlCondition.IsNull(scope.column(filter.column), filter.negated)
        is Filter.And -> SqlCondition.And(filter.filters.map { condition(it, scope) })
        is Filter.Or -> SqlCondition.Or(filter.filters.map { condition(it, scope) })
        is Filter.Not -> SqlCondition.Not(condition(filter.filter, scope))
        is Filter.Related -> related(filter, scope)
    }

    private fun compare(filter: Filter.Compare, scope: Scope): SqlCondition {
        val column = scope.column(filter.column)
        val insensitive = filter.mode == TextMatchMode.INSENSITIVE
        val match = when (filter.operator) {
            ComparisonOperator.CONTAINS -> SqlTextMatch.CONTAINS
            ComparisonOperator.STARTS_WITH -> SqlTextMatch.STARTS_WITH
            ComparisonOperator.ENDS_WITH -> SqlTextMatch.ENDS_WITH
            else -> null
        }
        if (match != null) {
            val value = filter.value as? String ?: throw VolanQueryException(
                "a text search on `${filter.column}` was given ${filter.value?.javaClass?.simpleName ?: "null"} rather than text.",
                null,
            )
            return SqlCondition.TextMatch(column, match, value, insensitive)
        }
        return SqlCondition.Compare(column, comparison(filter.operator), SqlExpression.Parameter(filter.value), insensitive)
    }

    private fun comparison(operator: ComparisonOperator): SqlComparison = when (operator) {
        ComparisonOperator.EQUAL -> SqlComparison.EQUAL
        ComparisonOperator.NOT_EQUAL -> SqlComparison.NOT_EQUAL
        ComparisonOperator.LESS_THAN -> SqlComparison.LESS_THAN
        ComparisonOperator.LESS_THAN_OR_EQUAL -> SqlComparison.LESS_THAN_OR_EQUAL
        ComparisonOperator.GREATER_THAN -> SqlComparison.GREATER_THAN
        ComparisonOperator.GREATER_THAN_OR_EQUAL -> SqlComparison.GREATER_THAN_OR_EQUAL
        ComparisonOperator.CONTAINS, ComparisonOperator.STARTS_WITH, ComparisonOperator.ENDS_WITH ->
            error("text matching is handled before this point")
    }

    /**
     * A condition on related rows becomes `EXISTS` over the other table, correlated on the key.
     *
     * `every` is the interesting one: "all of them match" is expressed as "none of them fails to",
     * which is also what makes it true for a row with no related rows at all.
     */
    private fun related(filter: Filter.Related, scope: Scope): SqlCondition {
        val relation = registry.requireRelation(scope.table.model, filter.relation)
        if (relation.joinTable != null) {
            throw VolanUnsupportedException(
                "filtering on the many-to-many relation `${scope.table.model}.${filter.relation}` is not available yet.\n" +
                    "  Filter on the join through a second query for now; it arrives with relation loading in M5.",
            )
        }
        val target = registry.require(relation.target)
        val inner = scope.nested(target)
        val correlation = correlate(relation, scope, inner)
        val nested = filter.filter?.let { condition(it, inner) }

        val negateEvery = filter.quantifier == RelationQuantifier.EVERY
        val body = when {
            negateEvery && nested != null -> SqlCondition.And(listOf(correlation, SqlCondition.Not(nested)))
            nested == null -> correlation
            else -> SqlCondition.And(listOf(correlation, nested))
        }
        val subquery = SqlSelect(
            table = target.table,
            alias = inner.alias,
            items = listOf(SqlSelectItem.Column(SqlExpression.Keyword("1"), alias = null)),
            condition = body,
        )
        val negated = when (filter.quantifier) {
            RelationQuantifier.SOME, RelationQuantifier.IS -> false
            RelationQuantifier.NONE, RelationQuantifier.IS_NOT, RelationQuantifier.EVERY -> true
        }
        return SqlCondition.Exists(subquery, negated)
    }

    private fun correlate(relation: RelationMetadata, outer: Scope, inner: Scope): SqlCondition {
        val local = if (relation.ownsForeignKey) relation.foreignKeyColumns else relation.referencedColumns
        val remote = if (relation.ownsForeignKey) relation.referencedColumns else relation.foreignKeyColumns
        val pairs = local.zip(remote).map { (localColumn, remoteColumn) ->
            SqlCondition.Compare(inner.column(remoteColumn), SqlComparison.EQUAL, outer.column(localColumn))
        }
        return allOf(pairs) ?: throw VolanConfigurationException(
            "the relation `${outer.table.model}.${relation.field}` has no columns to join on, " +
                "which means the generated client and this runtime disagree about it.",
        )
    }

    private fun orderBy(terms: List<OrderTerm>, scope: Scope): List<SqlOrder> = terms.map { term ->
        SqlOrder(
            expression = scope.column(term.column),
            descending = term.direction == SortDirection.DESCENDING,
            nulls = when (term.nulls) {
                NullsOrder.DEFAULT -> SqlNulls.DEFAULT
                NullsOrder.FIRST -> SqlNulls.FIRST
                NullsOrder.LAST -> SqlNulls.LAST
            },
        )
    }

    /**
     * Cursor paging compares the primary key as a tuple, which is the only comparison that does not
     * quietly skip rows when the key has more than one column.
     */
    private fun cursorCondition(spec: QuerySpec, scope: Scope, ordering: List<SqlOrder>): SqlCondition? {
        val cursor = spec.pagination.cursor ?: return null
        requireKeyOrdering(ordering)
        val key = scope.table.primaryKey
        requireTupleSupport(key, scope.table.model)
        val operator = if (spec.pagination.skipCursorRow) SqlComparison.GREATER_THAN else SqlComparison.GREATER_THAN_OR_EQUAL
        return SqlCondition.CompareTuple(key.map { scope.column(it) }, operator, cursorValues(key, cursor, scope.table))
    }

    private fun requireKeyOrdering(ordering: List<SqlOrder>) {
        if (ordering.isEmpty()) return
        throw VolanUnsupportedException(
            "a cursor cannot be combined with an explicit `orderBy` yet, because resuming after a row " +
                "requires knowing that row's position in that order.\n" +
                "  Use the cursor on its own, which pages by primary key, or use `skip` with your ordering.",
        )
    }

    private fun requireTupleSupport(key: List<String>, model: String) {
        if (key.size <= 1 || dialect.capabilities.tupleComparison) return
        throw VolanUnsupportedException(
            "${dialect.id} cannot compare a tuple, so a cursor over the composite key of `$model` cannot be expressed on it.",
        )
    }

    private fun cursorValues(key: List<String>, cursor: Map<String, Any?>, table: TableMetadata): List<SqlExpression> = key.map { column ->
        val field = table.columns.first { it.column == column }.field
        val value = cursor[field] ?: cursor[column] ?: throw VolanQueryException(
            "the cursor is missing a value for `$field`, which is part of the primary key of `${table.model}`.",
            null,
        )
        SqlExpression.Parameter(value)
    }

    private fun cursorOrdering(spec: QuerySpec, scope: Scope): List<SqlOrder> {
        if (spec.pagination.cursor == null) return emptyList()
        return scope.table.primaryKey.map { SqlOrder(scope.column(it), descending = false) }
    }

    private fun allOf(conditions: List<SqlCondition>): SqlCondition? = when (conditions.size) {
        0 -> null
        1 -> conditions[0]
        else -> SqlCondition.And(conditions)
    }

    /**
     * Hands out one alias per level of a statement.
     *
     * The counter is shared down the whole tree: two subqueries at the same depth must not end up
     * with the same name, or a correlated condition silently refers to the wrong table.
     */
    private class Aliases {
        private var next = 0

        fun allocate(): String = "t${next++}"
    }

    /** One level of a query: which table it reads and what that level is called inside the statement. */
    private class Scope(
        val table: TableMetadata,
        private val aliases: Aliases,
        val alias: String = aliases.allocate(),
    ) {
        fun column(name: String): SqlExpression.Column = SqlExpression.Column(alias, name)

        fun nested(target: TableMetadata): Scope = Scope(target, aliases)
    }

    private companion object {
        private const val COUNT_ALIAS = "count"
    }
}
