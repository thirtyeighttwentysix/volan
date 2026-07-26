package io.github.thirtyeighttwentysix.volan.migrate

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class MigrationDirectoryTest {
    @TempDir
    lateinit var root: Path

    private val moment = Instant.parse("2026-07-27T09:30:00Z")

    @Test
    fun `a written migration is named for when it was written and what it was called`() {
        val written = MigrationDirectory(root).write(moment, "add posts table", "CREATE TABLE \"Post\" ();\n")

        written.id shouldBe "20260727093000_add_posts_table"
        written.name shouldBe "add_posts_table"
    }

    @Test
    fun `a name with nothing usable in it still produces a directory`() {
        MigrationDirectory(root).write(moment, "!!!", "").id shouldEndWith "_migration"
    }

    @Test
    fun `migrations are read in the order their names give, not the order the filesystem gives`() {
        val directory = MigrationDirectory(root)
        write("20260727093000_second", "SELECT 2;")
        write("20260101000000_first", "SELECT 1;")

        directory.read().map { it.id } shouldContainExactly listOf("20260101000000_first", "20260727093000_second")
    }

    @Test
    fun `a directory with no script in it is not a migration`() {
        root.resolve("20260101000000_notes").createDirectories()

        MigrationDirectory(root).read().shouldContainExactly(emptyList())
    }

    @Test
    fun `a directory that does not exist yet holds no migrations`() {
        MigrationDirectory(root.resolve("nothing")).read().shouldContainExactly(emptyList())
    }

    @Test
    fun `a checksum notices an edit and ignores a change of line endings`() {
        val unix = MigrationFile("20260101000000_a", "CREATE TABLE \"a\" ();\nCREATE TABLE \"b\" ();\n")
        val windows = MigrationFile("20260101000000_a", "CREATE TABLE \"a\" ();\r\nCREATE TABLE \"b\" ();\r\n")
        val edited = MigrationFile("20260101000000_a", "CREATE TABLE \"a\" ();\nDROP TABLE \"b\";\n")

        unix.checksum shouldBe windows.checksum
        (unix.checksum == edited.checksum) shouldBe false
        unix.checksum.length shouldBe 64
        unix.checksum shouldStartWith unix.checksum.take(1)
    }

    private fun write(id: String, sql: String) {
        val directory = root.resolve(id)
        directory.createDirectories()
        directory.resolve("migration.sql").writeText(sql)
    }
}
