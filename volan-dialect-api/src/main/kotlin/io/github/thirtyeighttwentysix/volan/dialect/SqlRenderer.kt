package io.github.thirtyeighttwentysix.volan.dialect

/**
 * Renders Volan's SQL model as standard SQL, leaving each database to override only what it does
 * differently.
 *
 * Every value the caller supplied leaves through [Builder.bind] and appears in the text as `?`. There
 * is no method here that puts a value into the string, which is what makes the guarantee structural
 * rather than a matter of remembering.
 */
public abstract class SqlRenderer(
    /** What the database can do; the renderer consults this instead of asking which database it is. */
    override val capabilities: DialectCapabilities,
) : Dialect {
    override fun render(select: SqlSelect): SqlStatement = build { appendSelect(select) }

    override fun render(insert: SqlInsert): SqlStatement = build { appendInsert(insert) }

    override fun render(update: SqlUpdate): SqlStatement = build { appendUpdate(update) }

    override fun render(delete: SqlDelete): SqlStatement = build { appendDelete(delete) }

    override fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /** The character that escapes a wildcard inside a `LIKE` pattern. */
    protected open val likeEscape: Char get() = '\\'

    /** The keyword for a case-insensitive `LIKE`, when the database has one. */
    protected open val caseInsensitiveLikeKeyword: String get() = "ILIKE"

    protected open fun Builder.appendSelect(select: SqlSelect) {
        append("SELECT ")
        if (select.distinctOn.isNotEmpty()) appendDistinct(select)
        appendItems(select.items)
        append(" FROM ").append(qualified(select.table, select.alias))
        select.condition?.let {
            append(" WHERE ")
            appendCondition(it)
        }
        if (select.groupBy.isNotEmpty()) {
            append(" GROUP BY ")
            select.groupBy.forEachIndexed { index, expression ->
                if (index > 0) append(", ")
                appendExpression(expression)
            }
        }
        select.having?.let {
            append(" HAVING ")
            appendCondition(it)
        }
        appendOrderBy(select.orderBy)
        appendLimit(select.limit, select.offset)
    }

    protected open fun Builder.appendDistinct(select: SqlSelect) {
        if (!capabilities.distinctOn) {
            append("DISTINCT ")
            return
        }
        append("DISTINCT ON (")
        select.distinctOn.forEachIndexed { index, expression ->
            if (index > 0) append(", ")
            appendExpression(expression)
        }
        append(") ")
    }

    protected open fun Builder.appendItems(items: List<SqlSelectItem>) {
        if (items.isEmpty()) {
            append("*")
            return
        }
        items.forEachIndexed { index, item ->
            if (index > 0) append(", ")
            when (item) {
                is SqlSelectItem.Column -> {
                    appendExpression(item.expression)
                    item.alias?.let { append(" AS ").append(quote(it)) }
                }
                is SqlSelectItem.CountAll -> append("COUNT(*) AS ").append(quote(item.alias))
                is SqlSelectItem.Aggregate -> {
                    append(item.function.function).append('(')
                    appendExpression(item.expression)
                    append(") AS ").append(quote(item.alias))
                }
            }
        }
    }

    protected open fun Builder.appendOrderBy(terms: List<SqlOrder>) {
        if (terms.isEmpty()) return
        append(" ORDER BY ")
        terms.forEachIndexed { index, term ->
            if (index > 0) append(", ")
            appendExpression(term.expression)
            append(if (term.descending) " DESC" else " ASC")
            appendNulls(term)
        }
    }

    protected open fun Builder.appendNulls(term: SqlOrder) {
        if (term.nulls == SqlNulls.DEFAULT) return
        if (!capabilities.nullsOrdering) return
        append(if (term.nulls == SqlNulls.FIRST) " NULLS FIRST" else " NULLS LAST")
    }

    protected open fun Builder.appendLimit(limit: Int?, offset: Int?) {
        limit?.let {
            append(" LIMIT ")
            bind(it)
        }
        offset?.let {
            append(" OFFSET ")
            bind(it)
        }
    }

    protected open fun Builder.appendInsert(insert: SqlInsert) {
        append("INSERT INTO ").append(quote(insert.table))
        if (insert.columns.isEmpty()) {
            append(' ').append(defaultValuesClause)
        } else {
            append(" (")
            insert.columns.forEachIndexed { index, column ->
                if (index > 0) append(", ")
                append(quote(column))
            }
            append(") VALUES ")
            insert.rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) append(", ")
                append('(')
                row.forEachIndexed { index, value ->
                    if (index > 0) append(", ")
                    appendExpression(value)
                }
                append(')')
            }
        }
        appendReturning(insert.returning)
    }

    protected open fun Builder.appendUpdate(update: SqlUpdate) {
        append("UPDATE ").append(quote(update.table)).append(" SET ")
        update.assignments.forEachIndexed { index, assignment ->
            if (index > 0) append(", ")
            append(quote(assignment.column)).append(" = ")
            appendExpression(assignment.value)
        }
        update.condition?.let {
            append(" WHERE ")
            appendCondition(it)
        }
        appendReturning(update.returning)
    }

    protected open fun Builder.appendDelete(delete: SqlDelete) {
        append("DELETE FROM ").append(quote(delete.table))
        delete.condition?.let {
            append(" WHERE ")
            appendCondition(it)
        }
        appendReturning(delete.returning)
    }

    protected open fun Builder.appendReturning(columns: List<String>) {
        if (columns.isEmpty() || !capabilities.returningClause) return
        append(" RETURNING ")
        columns.forEachIndexed { index, column ->
            if (index > 0) append(", ")
            append(quote(column))
        }
    }

    /** What an insert with no columns writes; every database spells this differently. */
    protected open val defaultValuesClause: String get() = "DEFAULT VALUES"

    @Suppress("CyclomaticComplexMethod")
    protected open fun Builder.appendCondition(condition: SqlCondition) {
        when (condition) {
            is SqlCondition.Compare -> appendCompare(condition)
            is SqlCondition.TextMatch -> appendTextMatch(condition)
            is SqlCondition.Between -> {
                appendExpression(condition.column)
                append(" BETWEEN ")
                appendExpression(condition.lower)
                append(" AND ")
                appendExpression(condition.upper)
            }
            is SqlCondition.InList -> appendInList(condition)
            is SqlCondition.IsNull -> {
                appendExpression(condition.column)
                append(if (condition.negated) " IS NOT NULL" else " IS NULL")
            }
            is SqlCondition.And -> appendGroup(condition.conditions, "AND", emptyMatches = true)
            is SqlCondition.Or -> appendGroup(condition.conditions, "OR", emptyMatches = false)
            is SqlCondition.Not -> {
                append("NOT (")
                appendCondition(condition.condition)
                append(')')
            }
            is SqlCondition.Exists -> {
                append(if (condition.negated) "NOT EXISTS (" else "EXISTS (")
                appendSelect(condition.subquery)
                append(')')
            }
            is SqlCondition.CompareTuple -> appendTuple(condition)
        }
    }

    protected open fun Builder.appendCompare(condition: SqlCondition.Compare) {
        if (condition.caseInsensitive) {
            append("LOWER(")
            appendExpression(condition.left)
            append(") ").append(condition.operator.symbol).append(" LOWER(")
            appendExpression(condition.right)
            append(')')
            return
        }
        appendExpression(condition.left)
        append(' ').append(condition.operator.symbol).append(' ')
        appendExpression(condition.right)
    }

    protected open fun Builder.appendTextMatch(condition: SqlCondition.TextMatch) {
        val pattern = pattern(condition.match, condition.value)
        if (condition.caseInsensitive && capabilities.caseInsensitiveLike) {
            appendExpression(condition.column)
            append(' ').append(caseInsensitiveLikeKeyword).append(' ')
            bind(pattern)
        } else if (condition.caseInsensitive) {
            append("LOWER(")
            appendExpression(condition.column)
            append(") LIKE LOWER(")
            bind(pattern)
            append(')')
        } else {
            appendExpression(condition.column)
            append(" LIKE ")
            bind(pattern)
        }
        append(" ESCAPE '").append(likeEscape).append('\'')
    }

    protected open fun Builder.appendInList(condition: SqlCondition.InList) {
        if (condition.values.isEmpty()) {
            // `x IN ()` is a syntax error, and the answer is known anyway.
            append(if (condition.negated) "1 = 1" else "1 = 0")
            return
        }
        appendExpression(condition.column)
        append(if (condition.negated) " NOT IN (" else " IN (")
        condition.values.forEachIndexed { index, value ->
            if (index > 0) append(", ")
            appendExpression(value)
        }
        append(')')
    }

    protected open fun Builder.appendGroup(conditions: List<SqlCondition>, keyword: String, emptyMatches: Boolean) {
        if (conditions.isEmpty()) {
            append(if (emptyMatches) "1 = 1" else "1 = 0")
            return
        }
        conditions.forEachIndexed { index, nested ->
            if (index > 0) append(' ').append(keyword).append(' ')
            append('(')
            appendCondition(nested)
            append(')')
        }
    }

    protected open fun Builder.appendTuple(condition: SqlCondition.CompareTuple) {
        append('(')
        condition.columns.forEachIndexed { index, column ->
            if (index > 0) append(", ")
            appendExpression(column)
        }
        append(") ").append(condition.operator.symbol).append(" (")
        condition.values.forEachIndexed { index, value ->
            if (index > 0) append(", ")
            appendExpression(value)
        }
        append(')')
    }

    protected open fun Builder.appendExpression(expression: SqlExpression) {
        when (expression) {
            is SqlExpression.Column -> {
                expression.table?.let { append(quote(it)).append('.') }
                append(quote(expression.name))
            }
            is SqlExpression.Parameter -> bind(expression.value)
            is SqlExpression.Keyword -> append(expression.text)
            is SqlExpression.Call -> {
                append(expression.name).append('(')
                expression.arguments.forEachIndexed { index, argument ->
                    if (index > 0) append(", ")
                    appendExpression(argument)
                }
                append(')')
            }
        }
    }

    protected fun qualified(table: String, alias: String?): String =
        if (alias == null) quote(table) else quote(table) + " AS " + quote(alias)

    /**
     * Turns a search term into a `LIKE` pattern, escaping the wildcards it contains.
     *
     * Without this, searching for `100%` would match everything beginning with `100`, and searching
     * for `a_b` would match `axb` — surprises that only show up once real data arrives.
     */
    protected fun pattern(match: SqlTextMatch, value: String): String {
        val escaped = buildString {
            value.forEach { character ->
                if (character == '%' || character == '_' || character == likeEscape) append(likeEscape)
                append(character)
            }
        }
        return when (match) {
            SqlTextMatch.CONTAINS -> "%$escaped%"
            SqlTextMatch.STARTS_WITH -> "$escaped%"
            SqlTextMatch.ENDS_WITH -> "%$escaped"
        }
    }

    private fun build(block: Builder.() -> Unit): SqlStatement {
        val builder = Builder()
        builder.block()
        return builder.toStatement()
    }

    /**
     * Collects statement text and the values to bind to it.
     *
     * Text goes in through [append], values through [bind]. There is deliberately no way to put a
     * value into the text.
     */
    protected class Builder {
        private val sql = StringBuilder()
        private val parameters = ArrayList<Any?>()

        /** Appends statement text. Never call this with anything a caller supplied. */
        public fun append(text: String): Builder {
            sql.append(text)
            return this
        }

        /** Appends a single character of statement text. */
        public fun append(character: Char): Builder {
            sql.append(character)
            return this
        }

        /** Appends a number Volan itself produced, such as a chunk size. */
        public fun append(number: Int): Builder {
            sql.append(number)
            return this
        }

        /** Binds [value] as a parameter and writes its placeholder. */
        public fun bind(value: Any?): Builder {
            parameters.add(value)
            sql.append('?')
            return this
        }

        internal fun toStatement(): SqlStatement = SqlStatement(sql.toString(), parameters.toList())
    }
}
