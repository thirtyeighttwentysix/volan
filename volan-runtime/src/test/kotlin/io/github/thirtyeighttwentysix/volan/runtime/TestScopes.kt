package io.github.thirtyeighttwentysix.volan.runtime

/**
 * Hand-written stand-ins for what the generator will emit for the `User` model of the test schema.
 *
 * They exist so that the query description layer is pinned down before the generator depends on it:
 * if a change here breaks these, it would have broken every generated client.
 */
internal enum class Role(val databaseValue: String) {
    USER("USER"),
    ADMIN("administrator"),
}

internal class UserWhere : FilterScope() {
    val id: OrderedFilterField<Int> = orderedField("id")
    val email: TextFilterField = textField("email")
    val name: TextFilterField = textField("name")
    val role: EnumFilterField<Role> = enumField("role") { it.databaseValue }

    fun or(block: UserWhere.() -> Unit) {
        recordAnyOf(UserWhere().apply(block))
    }

    fun and(block: UserWhere.() -> Unit) {
        recordAllOf(UserWhere().apply(block))
    }

    fun not(block: UserWhere.() -> Unit) {
        recordNoneOf(UserWhere().apply(block))
    }

    fun posts(block: PostRelationFilter.() -> Unit) {
        PostRelationFilter { quantifier, nested -> recordRelated("posts", quantifier, nested) }.apply(block)
    }
}

internal class PostWhere : FilterScope() {
    val title: TextFilterField = textField("title")
}

internal class PostRelationFilter(private val sink: (RelationQuantifier, FilterScope) -> Unit) {
    fun some(block: PostWhere.() -> Unit) {
        sink(RelationQuantifier.SOME, PostWhere().apply(block))
    }

    fun none(block: PostWhere.() -> Unit) {
        sink(RelationQuantifier.NONE, PostWhere().apply(block))
    }
}

internal class UserOrderBy : OrderScope() {
    val createdAt: OrderField = orderField("created_at")
    val email: OrderField = orderField("email")
}

internal class UserSelect : SelectScope() {
    val email: Unit get() = markSelected("email")
    val name: Unit get() = markSelected("name")
}

internal class UserInclude : IncludeScope() {
    fun posts(block: PostQuery.() -> Unit) {
        includeRelation("posts", PostQuery().apply(block).build())
    }
}

internal class PostQuery : QueryScope("Post") {
    fun where(block: PostWhere.() -> Unit) {
        recordFilter(PostWhere().apply(block))
    }
}

internal class UserNumericFields : SelectScope() {
    val id: Unit get() = markSelected("id")
}

internal class UserAggregateScope : AggregateScope("User") {
    fun where(block: UserWhere.() -> Unit) {
        recordFilter(UserWhere().apply(block))
    }

    fun count() {
        record(AggregateFunction.COUNT, null, "count")
    }

    fun sum(block: UserNumericFields.() -> Unit) {
        UserNumericFields().apply(block).build().forEach { record(AggregateFunction.SUM, it, "sum_$it") }
    }

    fun average(block: UserNumericFields.() -> Unit) {
        UserNumericFields().apply(block).build().forEach { record(AggregateFunction.AVERAGE, it, "avg_$it") }
    }
}

internal class UserQuery : QueryScope("User") {
    fun where(block: UserWhere.() -> Unit) {
        recordFilter(UserWhere().apply(block))
    }

    fun orderBy(block: UserOrderBy.() -> Unit) {
        recordOrder(UserOrderBy().apply(block))
    }

    fun select(block: UserSelect.() -> Unit) {
        recordSelection(UserSelect().apply(block).build().toList())
    }

    fun include(block: UserInclude.() -> Unit) {
        recordIncludes(UserInclude().apply(block))
    }

    fun cursor(id: Int, inclusive: Boolean = false) {
        recordCursor(mapOf("id" to id), inclusive)
    }
}
