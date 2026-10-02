package io.github.thirtyeighttwentysix.volan.dialect.mysql

import io.github.thirtyeighttwentysix.volan.dialect.ColumnChange
import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefinition
import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.ConstraintKind
import io.github.thirtyeighttwentysix.volan.dialect.DdlStatement
import io.github.thirtyeighttwentysix.volan.dialect.SqlExpression
import io.github.thirtyeighttwentysix.volan.dialect.SqlInsert
import io.github.thirtyeighttwentysix.volan.dialect.SqlSelect
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.dialect.VolanDialectException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MySqlRenderingTest {
    @Test fun `identifiers are quoted and write values stay bound`() {
        val sql = MySqlDialect.render(SqlInsert("order`table", listOf("name"), listOf(listOf(SqlExpression.Parameter("O'Reilly")))))
        sql.sql shouldBe "INSERT INTO `order``table` (`name`) VALUES (?)"
        sql.parameters shouldBe listOf("O'Reilly")
        MySqlDialect.render(SqlInsert("t", emptyList(), listOf(emptyList()))).sql shouldBe "INSERT INTO `t` () VALUES ()"
    }

    @Test fun `constraint removal uses its catalogue kind`() {
        val expected = mapOf(
            ConstraintKind.PRIMARY_KEY to "DROP PRIMARY KEY",
            ConstraintKind.UNIQUE to "DROP INDEX `key`",
            ConstraintKind.FOREIGN_KEY to "DROP FOREIGN KEY `key`",
        )
        expected.forEach { (kind, sql) ->
            MySqlDialect.render(DdlStatement.DropConstraint("t", "key", kind)).single().sql shouldBe "ALTER TABLE `t` $sql"
        }
        shouldThrow<VolanDialectException> { MySqlDialect.render(DdlStatement.DropConstraint("t", "key")) }
    }

    @Test fun `unsupported storage and incomplete column changes fail before SQL runs`() {
        for (type in listOf(ColumnType.Array(ColumnType.Scalar(SqlType.TEXT)), ColumnType.Native("VarChar", listOf("20")))) {
            shouldThrow<VolanDialectException> { MySqlDialect.render(DdlStatement.AddColumn("t", ColumnDefinition("c", type, true))) }
        }
        shouldThrow<VolanDialectException> {
            MySqlDialect.render(DdlStatement.AlterColumn("t", "name", ColumnChange.Nullability(false)))
        }
        shouldThrow<VolanDialectException> {
            val column = ColumnDefinition("id", ColumnType.Scalar(SqlType.TEXT), false, autoIncrement = true)
            MySqlDialect.render(DdlStatement.CreateTable("t", listOf(column)))
        }
    }

    @Test fun `locking reads and offset only queries keep MySQL syntax`() {
        val select = SqlSelect("t", offset = 2)
        MySqlDialect.render(select).sql shouldBe "SELECT * FROM `t` LIMIT 18446744073709551615 OFFSET ?"
        MySqlDialect.renderForUpdate(select).sql shouldBe "SELECT * FROM `t` LIMIT 18446744073709551615 OFFSET ? FOR UPDATE"
        MySqlDialect.render(select).parameters shouldBe listOf(2)
    }
}
