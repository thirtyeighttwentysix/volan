package io.github.thirtyeighttwentysix.volan.migrate

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.nio.file.Path

class StatementSplitTest {
    private val migrator = Migrator(MigrationDirectory(Path.of("build/unused")))

    @Test
    fun `statements are separated by the semicolons between them`() {
        migrator.split("CREATE TABLE \"a\" ();\n\nDROP TABLE \"b\";\n") shouldContainExactly listOf(
            "CREATE TABLE \"a\" ()",
            "DROP TABLE \"b\"",
        )
    }

    @Test
    fun `a semicolon inside a string is part of the string`() {
        migrator.split("INSERT INTO \"a\" VALUES ('one; two');") shouldContainExactly listOf(
            "INSERT INTO \"a\" VALUES ('one; two')",
        )
    }

    @Test
    fun `a semicolon inside a quoted name is part of the name`() {
        migrator.split("DROP TABLE \"odd;name\";") shouldContainExactly listOf("DROP TABLE \"odd;name\"")
    }

    @Test
    fun `a comment is not a statement, and a semicolon in one ends nothing`() {
        val sql = """
            -- drops the old table; the new one replaces it
            DROP TABLE "old";
        """.trimIndent()

        migrator.split(sql) shouldContainExactly listOf("DROP TABLE \"old\"")
    }

    @Test
    fun `a script that forgot its last semicolon still ends with a statement`() {
        migrator.split("DROP TABLE \"a\"") shouldContainExactly listOf("DROP TABLE \"a\"")
    }

    @Test
    fun `an empty script holds no statements`() {
        migrator.split("\n\n;\n") shouldContainExactly emptyList()
    }
}
