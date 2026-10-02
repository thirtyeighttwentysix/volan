package io.github.thirtyeighttwentysix.volan.migrate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SqlScriptTest {
    @Test fun `backtick identifiers retain semicolons apostrophes and escaped backticks`() {
        val sql = "CREATE TABLE `we'ird;name``x` (`id` INT); SELECT 1;"
        SqlScript(sql, mysql = true).split() shouldBe listOf("CREATE TABLE `we'ird;name``x` (`id` INT)", "SELECT 1")
        shouldThrow<VolanMigrationException> { SqlScript("CREATE TABLE `unfinished", mysql = true).split() }
    }

    @Test fun `MySQL hash comments do not mask a session command and subtraction remains SQL`() {
        SqlScript("# comment;\nSET sql_mode=''; SELECT 1--2; -- comment\nSELECT 3", mysql = true).split() shouldBe
            listOf("SET sql_mode=''", "SELECT 1--2", "SELECT 3")
        SqlScript("SELECT 1 # 2").split() shouldBe listOf("SELECT 1 # 2")
    }

    @Test fun `executable comments are refused rather than silently discarding their SQL`() {
        for (sql in listOf("SELECT 1; /*! SET sql_mode='' */", "/*M! SELECT 1 */")) {
            shouldThrow<VolanMigrationException> { SqlScript(sql, mysql = true).split() }
        }
    }
}
