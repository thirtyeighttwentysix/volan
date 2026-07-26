package io.github.thirtyeighttwentysix.volan.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

class AggregateDescriptionTest {
    @Test
    fun `a block collects every summary it asked for, in the order it asked`() {
        val spec = UserAggregateScope().apply {
            count()
            average { id }
            sum { id }
        }.build()

        spec.model shouldBe "User"
        spec.aggregations shouldContainExactly listOf(
            Aggregation(AggregateFunction.COUNT, null, "count"),
            Aggregation(AggregateFunction.AVERAGE, "id", "avg_id"),
            Aggregation(AggregateFunction.SUM, "id", "sum_id"),
        )
    }

    @Test
    fun `asking for the same summary twice asks the database for it once`() {
        val spec = UserAggregateScope().apply {
            sum { id }
            sum { id }
        }.build()

        spec.aggregations shouldContainExactly listOf(Aggregation(AggregateFunction.SUM, "id", "sum_id"))
    }

    @Test
    fun `a where block narrows which rows are summarised`() {
        val spec = UserAggregateScope().apply {
            where { role eq Role.ADMIN }
            count()
        }.build()

        spec.filter shouldBe Filter.Compare("role", ComparisonOperator.EQUAL, "administrator")
    }

    @Test
    fun `summarising every row leaves the description without a condition`() {
        UserAggregateScope().apply { count() }.build().filter.shouldBeNull()
    }

    @Test
    fun `reading a summary the query did not ask for says how to ask for it`() {
        val failure = shouldThrow<VolanAggregateNotAskedException> {
            AggregateValues.decimal(emptyMap(), "sum_id", "the total of `User.id`")
        }

        failure.alias shouldBe "sum_id"
        failure.message.shouldContain("Ask for it in the `aggregate` block")
    }

    @Test
    fun `a summary over no rows reads as absent rather than as zero`() {
        AggregateValues.decimal(mapOf("sum_id" to null), "sum_id", "the total").shouldBeNull()
        AggregateValues.double(mapOf("avg_id" to null), "avg_id", "the mean").shouldBeNull()
    }

    @Test
    fun `a count is read as a count whichever integral type the driver chose`() {
        AggregateValues.count(mapOf("count" to 7), "count", "how many") shouldBe 7L
        AggregateValues.count(mapOf("count" to BigDecimal("7")), "count", "how many") shouldBe 7L
    }

    @Test
    fun `a total keeps its precision whichever numeric type the driver chose`() {
        AggregateValues.decimal(mapOf("sum" to 3L), "sum", "the total") shouldBe BigDecimal("3")
        AggregateValues.decimal(mapOf("sum" to BigDecimal("3.50")), "sum", "the total") shouldBe BigDecimal("3.50")
    }

    @Test
    fun `a smallest moment in time is read whichever shape the driver handed out`() {
        val moment = Instant.parse("2024-03-01T10:15:30Z")

        AggregateValues.instant(mapOf("min" to moment), "min", "the smallest") shouldBe moment
        AggregateValues.instant(mapOf("min" to Timestamp.from(moment)), "min", "the smallest") shouldBe moment
    }

    @Test
    fun `a value the driver returned in a shape Volan cannot read says which shape that was`() {
        val failure = shouldThrow<VolanMappingException> {
            AggregateValues.instant(mapOf("min" to "yesterday"), "min", "the smallest")
        }

        failure.message.shouldContain("java.lang.String")
    }
}
