package io.github.thirtyeighttwentysix.volan.dialect.sqlite

import io.github.thirtyeighttwentysix.volan.dialect.SqlCondition
import io.github.thirtyeighttwentysix.volan.dialect.SqlExpression
import io.github.thirtyeighttwentysix.volan.dialect.SqlInsert
import io.github.thirtyeighttwentysix.volan.dialect.SqlSelect
import io.github.thirtyeighttwentysix.volan.dialect.SqlSelectItem
import io.github.thirtyeighttwentysix.volan.dialect.SqlTextMatch
import io.github.thirtyeighttwentysix.volan.dialect.VolanDialectException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.DriverManager
import java.sql.SQLException

class SqliteRenderingTest {
    @Test
    fun `initialization refuses a connection that cannot enable foreign keys`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.autoCommit = false
            assertThrows<SQLException> { SqliteDialect.initialize(connection) }
            connection.rollback()
            connection.autoCommit = true
            SqliteDialect.initialize(connection)
        }
    }

    @Test
    fun `offset without a limit emits SQLite sentinel`() {
        val statement = SqliteDialect.render(SqlSelect("users", offset = 10))
        statement.sql shouldBe "SELECT * FROM \"users\" LIMIT -1 OFFSET ?"
        statement.parameters shouldContainExactly listOf(10)
    }

    @Test
    fun `glob text patterns keep input out of SQL and escape wildcard syntax`() {
        val value = "*?[a]'; DROP TABLE users; --"
        val statement = SqliteDialect.render(
            SqlSelect("users", condition = SqlCondition.TextMatch(SqlExpression.Column(null, "email"), SqlTextMatch.CONTAINS, value)),
        )
        statement.sql shouldBe "SELECT * FROM \"users\" WHERE \"email\" GLOB ?"
        statement.sql shouldNotContain "DROP TABLE"
        statement.parameters shouldContainExactly listOf("*[*][?][[]a]'; DROP TABLE users; --*")
    }

    @Test
    fun `default values in VALUES and partial distinct shapes cannot be silently rendered`() {
        assertThrows<VolanDialectException> {
            SqliteDialect.render(SqlInsert("users", listOf("name"), listOf(listOf(SqlExpression.Keyword("DEFAULT")))))
        }
        val id = SqlExpression.Column(null, "id")
        val name = SqlExpression.Column(null, "name")
        assertThrows<VolanDialectException> {
            SqliteDialect.render(
                SqlSelect(
                    "users",
                    items = listOf(SqlSelectItem.Column(id, null), SqlSelectItem.Column(name, null)),
                    distinctOn = listOf(name),
                ),
            )
        }
    }

    @Test
    fun `extended SQLite error codes produce portable SQL states without parsing messages`() {
        SqliteDialect.sqlState(SQLiteException("localized message", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)) shouldBe "23505"
        SqliteDialect.sqlState(SQLiteException("localized message", SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY)) shouldBe "23503"
        SqliteDialect.sqlState(SQLiteException("localized message", SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT)) shouldBe "40001"
    }

    @Test
    fun `provider selects only SQLite JDBC URLs`() {
        val provider = SqliteDialectProvider()
        provider.supports("jdbc:sqlite::memory:") shouldBe true
        provider.supports("jdbc:postgresql://localhost/db") shouldBe false
        provider.dialect() shouldBe SqliteDialect
    }
}
