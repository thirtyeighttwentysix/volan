package io.github.thirtyeighttwentysix.volan.runtime

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

class QueryDescriptionTest {
    @Test
    fun `conditions written one after another are combined with and`() {
        val where = UserWhere().apply {
            email endsWith "@acme.com"
            role eq Role.ADMIN
        }
        where.build() shouldBe Filter.And(
            listOf(
                Filter.Compare("email", ComparisonOperator.ENDS_WITH, "@acme.com"),
                Filter.Compare("role", ComparisonOperator.EQUAL, "administrator"),
            ),
        )
    }

    @Test
    fun `a single condition is not wrapped in an and`() {
        UserWhere().apply { id eq 1 }.build() shouldBe Filter.Compare("id", ComparisonOperator.EQUAL, 1)
    }

    @Test
    fun `an empty where block constrains nothing`() {
        UserWhere().build().shouldBeNull()
    }

    @Test
    fun `enum values are compared as the database stores them`() {
        val filter = UserWhere().apply { role eq Role.ADMIN }.build().shouldBeInstanceOf<Filter.Compare>()
        filter.value shouldBe "administrator"
    }

    @Test
    fun `or opens a nested scope with the same fields`() {
        val where = UserWhere().apply {
            email endsWith "@acme.com"
            or {
                name.isNull()
                id gt 10
            }
        }
        where.build() shouldBe Filter.And(
            listOf(
                Filter.Compare("email", ComparisonOperator.ENDS_WITH, "@acme.com"),
                Filter.Or(
                    listOf(
                        Filter.IsNull("name"),
                        Filter.Compare("id", ComparisonOperator.GREATER_THAN, 10),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `not negates everything its block collected`() {
        UserWhere().apply { not { id eq 1 } }.build() shouldBe
            Filter.Not(Filter.Compare("id", ComparisonOperator.EQUAL, 1))
    }

    @Test
    fun `an empty nested block adds nothing`() {
        UserWhere().apply { or { } }.build().shouldBeNull()
    }

    @Test
    fun `text matching can ignore case`() {
        val filter = UserWhere().apply { email.ignoringCase() eq "Alice@Acme.com" }.build()
            .shouldBeInstanceOf<Filter.Compare>()
        filter.mode shouldBe TextMatchMode.INSENSITIVE
        UserWhere().apply { email eq "a" }.build().shouldBeInstanceOf<Filter.Compare>().mode shouldBe
            TextMatchMode.SENSITIVE
    }

    @Test
    fun `ranges and membership are described, never rendered`() {
        val where = UserWhere().apply {
            id.between(1, 10)
            id oneOf listOf(1, 2, 3)
            name notOneOf listOf("root")
        }
        where.build() shouldBe Filter.And(
            listOf(
                Filter.Between("id", 1, 10),
                Filter.InList("id", listOf(1, 2, 3)),
                Filter.InList("name", listOf("root"), negated = true),
            ),
        )
    }

    @Test
    fun `relation filters carry the quantifier and the nested condition`() {
        val where = UserWhere().apply {
            posts { some { title contains "Kotlin" } }
        }
        where.build() shouldBe Filter.Related(
            "posts",
            RelationQuantifier.SOME,
            Filter.Compare("title", ComparisonOperator.CONTAINS, "Kotlin"),
        )
    }

    @Test
    fun `a relation filter with an empty block matches any related row`() {
        UserWhere().apply { posts { none { } } }.build() shouldBe
            Filter.Related("posts", RelationQuantifier.NONE, null)
    }

    @Test
    fun `order terms apply in the order they are written`() {
        UserOrderBy().apply {
            createdAt.desc()
            email.ascNullsLast()
        }.build() shouldContainExactly listOf(
            OrderTerm("created_at", SortDirection.DESCENDING, NullsOrder.DEFAULT),
            OrderTerm("email", SortDirection.ASCENDING, NullsOrder.LAST),
        )
    }

    @Test
    fun `select keeps the fields in the order they were named, without repeats`() {
        UserSelect().apply {
            email
            name
            email
        }.build() shouldContainExactly setOf("email", "name")
    }

    @Test
    fun `a query scope collects everything into one description`() {
        val spec = UserQuery().apply {
            where { email endsWith "@acme.com" }
            orderBy { createdAt.desc() }
            include { posts { where { title contains "Kotlin" } } }
            take = 20
            skip = 5
        }.build()

        spec.model shouldBe "User"
        spec.filter shouldBe Filter.Compare("email", ComparisonOperator.ENDS_WITH, "@acme.com")
        spec.orderBy shouldContainExactly listOf(OrderTerm("created_at", SortDirection.DESCENDING))
        spec.pagination shouldBe Pagination(take = 20, skip = 5)
        spec.columns.shouldBeNull()
        spec.includes shouldContainExactly listOf(
            RelationRequest(
                "posts",
                QuerySpec("Post", Filter.Compare("title", ComparisonOperator.CONTAINS, "Kotlin")),
            ),
        )
    }

    @Test
    fun `two where blocks in one query are combined rather than replacing each other`() {
        val spec = UserQuery().apply {
            where { id gt 1 }
            where { id lt 10 }
        }.build()
        spec.filter shouldBe Filter.And(
            listOf(
                Filter.Compare("id", ComparisonOperator.GREATER_THAN, 1),
                Filter.Compare("id", ComparisonOperator.LESS_THAN, 10),
            ),
        )
    }

    @Test
    fun `select narrows the columns a query reads`() {
        val spec = UserQuery().apply {
            select {
                email
                name
            }
        }.build()
        spec.columns shouldContainExactly listOf("email", "name")
    }

    @Test
    fun `distinct names the columns rows must differ in, and nothing by default`() {
        UserQuery().apply { }.build().distinct.shouldBeEmpty()
        UserQuery().apply { distinct { email } }.build().distinct shouldContainExactly listOf("email")
    }

    @Test
    fun `a cursor excludes its own row unless asked otherwise`() {
        UserQuery().apply { cursor(id = 7) }.build().pagination shouldBe
            Pagination(cursor = mapOf("id" to 7), skipCursorRow = true)
        UserQuery().apply { cursor(id = 7, inclusive = true) }.build().pagination shouldBe
            Pagination(cursor = mapOf("id" to 7), skipCursorRow = false)
    }

    @Test
    fun `instants are carried as values, not as text`() {
        val moment = Instant.parse("2026-07-26T10:15:30Z")
        val scope = object : FilterScope() {
            val createdAt: OrderedFilterField<Instant> = orderedField("created_at")
        }
        scope.createdAt gt moment
        scope.build() shouldBe Filter.Compare("created_at", ComparisonOperator.GREATER_THAN, moment)
    }

    @Test
    fun `filters combine helpers collapse the trivial cases`() {
        Filter.all(emptyList()).shouldBeNull()
        Filter.any(emptyList()).shouldBeNull()
        val one = Filter.IsNull("a")
        Filter.all(listOf(one)) shouldBe one
        Filter.any(listOf(one)) shouldBe one
    }

    @Test
    fun `values reach the description as values, never as text spliced into a query`() {
        val injection = "'; DROP TABLE users; --"
        val filter = UserWhere().apply { email eq injection }.build().shouldBeInstanceOf<Filter.Compare>()
        (filter.value === injection) shouldBe true
        filter.column shouldBe "email"
    }
}
