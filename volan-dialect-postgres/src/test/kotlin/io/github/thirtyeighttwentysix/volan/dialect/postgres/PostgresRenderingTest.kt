package io.github.thirtyeighttwentysix.volan.dialect.postgres

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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Golden SQL: the exact text PostgreSQL is sent, and the exact values bound to it.
 *
 * These are deliberately literal. A change to any of them is a change to what runs against a user's
 * database, and it should have to be made on purpose.
 */
class PostgresRenderingTest {
    private fun column(name: String) = SqlExpression.Column(null, name)

    private fun parameter(value: Any?) = SqlExpression.Parameter(value)

    @Test
    fun `a select with no items reads every column`() {
        val statement = PostgresDialect.render(SqlSelect(table = "users"))
        statement.sql shouldBe """SELECT * FROM "users""""
        statement.parameters.shouldBeEmpty()
    }

    @Test
    fun `columns and identifiers are quoted, so a column called order still works`() {
        val statement = PostgresDialect.render(
            SqlSelect(
                table = "users",
                items = listOf(SqlSelectItem.Column(column("order"), alias = null)),
            ),
        )
        statement.sql shouldBe """SELECT "order" FROM "users""""
    }

    @Test
    fun `a quote inside an identifier is doubled rather than ending it`() {
        PostgresDialect.quote("we\"ird") shouldBe "\"we\"\"ird\""
    }

    @Test
    fun `values are bound, never written into the statement`() {
        val injection = "'; DROP TABLE users; --"
        val statement = PostgresDialect.render(
            SqlSelect(
                table = "users",
                condition = SqlCondition.Compare(column("email"), SqlComparison.EQUAL, parameter(injection)),
            ),
        )
        statement.sql shouldBe """SELECT * FROM "users" WHERE "email" = ?"""
        statement.sql shouldNotContain "DROP"
        statement.parameters shouldContainExactly listOf(injection)
    }

    @Test
    fun `conditions are grouped so precedence cannot change with the data`() {
        val statement = PostgresDialect.render(
            SqlSelect(
                table = "users",
                condition = SqlCondition.And(
                    listOf(
                        SqlCondition.Compare(column("role"), SqlComparison.EQUAL, parameter("ADMIN")),
                        SqlCondition.Or(
                            listOf(
                                SqlCondition.IsNull(column("name")),
                                SqlCondition.Compare(column("id"), SqlComparison.GREATER_THAN, parameter(10)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        statement.sql shouldBe
            """SELECT * FROM "users" WHERE ("role" = ?) AND (("name" IS NULL) OR ("id" > ?))"""
        statement.parameters shouldContainExactly listOf("ADMIN", 10)
    }

    @Test
    fun `a text search escapes the wildcards the value contains`() {
        val statement = PostgresDialect.render(
            SqlSelect(
                table = "users",
                condition = SqlCondition.TextMatch(column("email"), SqlTextMatch.CONTAINS, "100%_off"),
            ),
        )
        statement.sql shouldBe """SELECT * FROM "users" WHERE "email" LIKE ? ESCAPE '\'"""
        statement.parameters shouldContainExactly listOf("%100\\%\\_off%")
    }

    @Test
    fun `prefix and suffix searches anchor the pattern on the right side`() {
        fun pattern(match: SqlTextMatch): Any? = PostgresDialect.render(
            SqlSelect(table = "t", condition = SqlCondition.TextMatch(column("c"), match, "a")),
        ).parameters.single()

        pattern(SqlTextMatch.STARTS_WITH) shouldBe "a%"
        pattern(SqlTextMatch.ENDS_WITH) shouldBe "%a"
        pattern(SqlTextMatch.CONTAINS) shouldBe "%a%"
    }

    @Test
    fun `a case-insensitive search uses ILIKE, which postgres has`() {
        val statement = PostgresDialect.render(
            SqlSelect(
                table = "users",
                condition = SqlCondition.TextMatch(column("email"), SqlTextMatch.ENDS_WITH, "@ACME.com", caseInsensitive = true),
            ),
        )
        statement.sql shouldBe """SELECT * FROM "users" WHERE "email" ILIKE ? ESCAPE '\'"""
    }

    @Test
    fun `a case-insensitive equality lowers both sides`() {
        val statement = PostgresDialect.render(
            SqlSelect(
                table = "users",
                condition = SqlCondition.Compare(column("email"), SqlComparison.EQUAL, parameter("A@B.c"), caseInsensitive = true),
            ),
        )
        statement.sql shouldBe """SELECT * FROM "users" WHERE LOWER("email") = LOWER(?)"""
    }

    @Test
    fun `an empty IN list becomes a condition that is simply false`() {
        val matches = PostgresDialect.render(
            SqlSelect(table = "t", condition = SqlCondition.InList(column("c"), emptyList())),
        )
        matches.sql shouldBe """SELECT * FROM "t" WHERE 1 = 0"""

        val excludes = PostgresDialect.render(
            SqlSelect(table = "t", condition = SqlCondition.InList(column("c"), emptyList(), negated = true)),
        )
        excludes.sql shouldBe """SELECT * FROM "t" WHERE 1 = 1"""
    }

    @Test
    fun `membership binds every value`() {
        val statement = PostgresDialect.render(
            SqlSelect(
                table = "t",
                condition = SqlCondition.InList(column("id"), listOf(parameter(1), parameter(2), parameter(3))),
            ),
        )
        statement.sql shouldBe """SELECT * FROM "t" WHERE "id" IN (?, ?, ?)"""
        statement.parameters shouldContainExactly listOf(1, 2, 3)
    }

    @Test
    fun `ordering carries direction and null placement`() {
        val statement = PostgresDialect.render(
            SqlSelect(
                table = "users",
                orderBy = listOf(
                    SqlOrder(column("created_at"), descending = true),
                    SqlOrder(column("name"), descending = false, nulls = SqlNulls.LAST),
                ),
            ),
        )
        statement.sql shouldBe """SELECT * FROM "users" ORDER BY "created_at" DESC, "name" ASC NULLS LAST"""
    }

    @Test
    fun `paging binds its numbers like any other value`() {
        val statement = PostgresDialect.render(SqlSelect(table = "users", limit = 20, offset = 5))
        statement.sql shouldBe """SELECT * FROM "users" LIMIT ? OFFSET ?"""
        statement.parameters shouldContainExactly listOf(20, 5)
    }

    @Test
    fun `a composite cursor compares tuples rather than columns one by one`() {
        val statement = PostgresDialect.render(
            SqlSelect(
                table = "post_comments",
                condition = SqlCondition.CompareTuple(
                    columns = listOf(column("post_id"), column("author_id")),
                    operator = SqlComparison.GREATER_THAN,
                    values = listOf(parameter(1L), parameter(2)),
                ),
            ),
        )
        statement.sql shouldBe """SELECT * FROM "post_comments" WHERE ("post_id", "author_id") > (?, ?)"""
    }

    @Test
    fun `a relation filter becomes a correlated subquery`() {
        val statement = PostgresDialect.render(
            SqlSelect(
                table = "users",
                alias = "u",
                condition = SqlCondition.Exists(
                    SqlSelect(
                        table = "posts",
                        alias = "p",
                        items = listOf(SqlSelectItem.Column(SqlExpression.Keyword("1"), alias = null)),
                        condition = SqlCondition.Compare(
                            SqlExpression.Column("p", "author_id"),
                            SqlComparison.EQUAL,
                            SqlExpression.Column("u", "id"),
                        ),
                    ),
                ),
            ),
        )
        statement.sql shouldBe
            """SELECT * FROM "users" AS "u" WHERE EXISTS (SELECT 1 FROM "posts" AS "p" WHERE "p"."author_id" = "u"."id")"""
    }

    @Test
    fun `distinct on is used where the database has it`() {
        val statement = PostgresDialect.render(SqlSelect(table = "users", distinctOn = listOf(column("email"))))
        statement.sql shouldBe """SELECT DISTINCT ON ("email") * FROM "users""""
    }

    @Test
    fun `an insert names its columns and reads the row back`() {
        val statement = PostgresDialect.render(
            SqlInsert(
                table = "users",
                columns = listOf("email", "name"),
                rows = listOf(listOf(parameter("a@b.c"), parameter("Alice"))),
                returning = listOf("id", "email", "name"),
            ),
        )
        statement.sql shouldBe
            """INSERT INTO "users" ("email", "name") VALUES (?, ?) RETURNING "id", "email", "name""""
        statement.parameters shouldContainExactly listOf("a@b.c", "Alice")
    }

    @Test
    fun `many rows go in one statement`() {
        val statement = PostgresDialect.render(
            SqlInsert(
                table = "tags",
                columns = listOf("name"),
                rows = listOf(listOf(parameter("kotlin")), listOf(parameter("jvm"))),
            ),
        )
        statement.sql shouldBe """INSERT INTO "tags" ("name") VALUES (?), (?)"""
        statement.parameters shouldContainExactly listOf("kotlin", "jvm")
    }

    @Test
    fun `an insert with nothing to say still inserts a row`() {
        val statement = PostgresDialect.render(SqlInsert(table = "events", columns = emptyList(), rows = emptyList()))
        statement.sql shouldBe """INSERT INTO "events" DEFAULT VALUES"""
    }

    @Test
    fun `an update sets what changed, where the filter says`() {
        val statement = PostgresDialect.render(
            SqlUpdate(
                table = "users",
                assignments = listOf(
                    SqlAssignment("name", parameter("Bob")),
                    SqlAssignment("updated_at", SqlExpression.Keyword("CURRENT_TIMESTAMP")),
                ),
                condition = SqlCondition.Compare(column("id"), SqlComparison.EQUAL, parameter(1)),
                returning = listOf("id"),
            ),
        )
        statement.sql shouldBe
            """UPDATE "users" SET "name" = ?, "updated_at" = CURRENT_TIMESTAMP WHERE "id" = ? RETURNING "id""""
        statement.parameters shouldContainExactly listOf("Bob", 1)
    }

    @Test
    fun `a delete without a filter is written as one, deliberately`() {
        PostgresDialect.render(SqlDelete(table = "sessions", condition = null)).sql shouldBe
            """DELETE FROM "sessions""""
    }

    @Test
    fun `aggregates and grouping are rendered with their aliases`() {
        val statement = PostgresDialect.render(
            SqlSelect(
                table = "posts",
                items = listOf(
                    SqlSelectItem.Column(column("author_id"), alias = null),
                    SqlSelectItem.CountAll("count"),
                ),
                groupBy = listOf(column("author_id")),
                having = SqlCondition.Compare(
                    SqlExpression.Call("COUNT", listOf(SqlExpression.Keyword("*"))),
                    SqlComparison.GREATER_THAN,
                    parameter(3),
                ),
            ),
        )
        statement.sql shouldBe
            """SELECT "author_id", COUNT(*) AS "count" FROM "posts" GROUP BY "author_id" HAVING COUNT(*) > ?"""
    }

    @Test
    fun `postgres declares what it can do`() {
        PostgresDialect.id shouldBe "postgresql"
        PostgresDialect.capabilities.returningClause shouldBe true
        PostgresDialect.capabilities.caseInsensitiveLike shouldBe true
        PostgresDialect.capabilities.tupleComparison shouldBe true
    }
}
