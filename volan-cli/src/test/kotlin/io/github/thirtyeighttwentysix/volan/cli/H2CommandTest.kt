package io.github.thirtyeighttwentysix.volan.cli

import com.github.ajalt.clikt.core.parse
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.readText

class H2CommandTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `pull writes a validated H2 schema and dry run preserves tables`() {
        val url = "jdbc:h2:file:${directory.resolve("data")}"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE \"Item\" (\"id\" INT PRIMARY KEY)") }
        }
        val schema = directory.resolve("schema.volan")
        command().parse(listOf("db", "pull", "--url", url, "--schema", schema.toString()))
        SchemaLoader.load(schema.toString(), schema.readText()).schemaOrThrow().datasource.provider.id shouldBe "h2"
        command().parse(listOf("db", "push", "--dry-run", "--url", url, "--schema", schema.toString()))
        command().parse(listOf("db", "push", "--url", url, "--schema", schema.toString()))
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'").use {
                    it.next()
                    it.getInt(1) shouldBe 1
                }
            }
        }
    }

    @Test
    fun `pull refuses overwriting a schema unless force was explicitly selected`() {
        val url = "jdbc:h2:file:${directory.resolve("data")}"
        val schema = directory.resolve("schema.volan")
        val args = listOf("db", "pull", "--url", url, "--schema", schema.toString())
        command().parse(args)
        val original = schema.readText()
        shouldThrow<java.nio.file.FileAlreadyExistsException> { command().parse(args) }
        schema.readText() shouldBe original
        command().parse(args + "--force")
        schema.readText() shouldBe original
    }
}
