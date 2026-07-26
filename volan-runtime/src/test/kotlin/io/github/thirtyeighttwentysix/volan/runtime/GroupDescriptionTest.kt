package io.github.thirtyeighttwentysix.volan.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import io.kotest.matchers.maps.shouldContainExactly as shouldContainExactlyEntries

class GroupDescriptionTest {
    @Test
    fun `a block collects what defines a group and what to work out about it`() {
        val spec = UserGroupScope().apply {
            by { role }
            where { name.isNotNull() }
            count()
            sum { id }
            orderBy { email.asc() }
            take = 5
            skip = 2
        }.build()

        spec.model shouldBe "User"
        spec.by shouldContainExactly listOf("role")
        spec.filter shouldBe Filter.IsNull("name", negated = true)
        spec.aggregations shouldContainExactly listOf(
            Aggregation(AggregateFunction.COUNT, null, "count"),
            Aggregation(AggregateFunction.SUM, "id", "sum_id"),
        )
        spec.orderBy.single().column shouldBe "email"
        spec.pagination.take shouldBe 5
        spec.pagination.skip shouldBe 2
    }

    @Test
    fun `naming a field twice groups by it once, keeping the order it was first named in`() {
        val spec = UserGroupScope().apply {
            by { role }
            by {
                id
                role
            }
        }.build()

        spec.by shouldContainExactly listOf("role", "id")
    }

    @Test
    fun `having is collected as a condition over the summaries, with what each one stands for`() {
        val spec = UserGroupScope().apply {
            by { role }
            count()
            having { count gt 2L }
        }.build()

        spec.having shouldBe Filter.Compare("count", ComparisonOperator.GREATER_THAN, 2L)
        spec.havingAggregations shouldContainExactlyEntries mapOf(
            "count" to Aggregation(AggregateFunction.COUNT, null, "count"),
            "sum_id" to Aggregation(AggregateFunction.SUM, "id", "sum_id"),
        )
    }

    @Test
    fun `several conditions in one having block all have to hold`() {
        val spec = UserGroupScope().apply {
            by { role }
            having {
                count gt 2L
                sumOfId lt BigDecimal("100")
            }
        }.build()

        spec.having shouldBe Filter.And(
            listOf(
                Filter.Compare("count", ComparisonOperator.GREATER_THAN, 2L),
                Filter.Compare("sum_id", ComparisonOperator.LESS_THAN, BigDecimal("100")),
            ),
        )
    }

    @Test
    fun `a grouping with no having leaves the description without one`() {
        val spec = UserGroupScope().apply { by { role } }.build()

        spec.having.shouldBeNull()
        spec.havingAggregations.isEmpty() shouldBe true
    }

    @Test
    fun `the fields a grouping collected are what the result will read its key by`() {
        val scope = UserGroupScope().apply { by { role } }

        scope.groupedFields() shouldBe setOf("role")
    }

    @Test
    fun `reading a group key the query did not group by names the block that would add it`() {
        val failure = shouldThrow<VolanFieldNotSelectedException> {
            SelectedFields.groupedBy(setOf("role")).require<Int>("User", "id", null)
        }

        failure.message.shouldContain("by { id }")
    }
}
