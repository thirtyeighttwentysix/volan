package io.github.thirtyeighttwentysix.volan.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class SchemaCommandTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `init validate and generation work without a database URL and refuse overwriting the schema`() {
        val schema = directory.resolve("schema.volan")
        command().parse(listOf("init", "--schema", schema.toString(), "--provider", "h2", "--java-friendly"))
        val original = schema.readText()
        command().parse(listOf("validate", "--schema", schema.toString()))
        val output = directory.resolve("generated")
        command().parse(listOf("generate", "--schema", schema.toString(), "--output", output.toString()))
        output.resolve("com/example/volan/VolanClient.kt").exists() shouldBe true
        shouldThrow<CliktError> { command().parse(listOf("init", "--schema", schema.toString())) }
        schema.readText() shouldBe original
    }

    @Test
    fun `format check and invalid schema diagnostics do not modify the file`() {
        val schema = directory.resolve("schema.volan")
        command().parse(listOf("init", "--schema", schema.toString()))
        schema.writeText(schema.readText().replace("  ", " "))
        val original = schema.readText()
        shouldThrow<CliktError> { command().parse(listOf("format", "--check", "--schema", schema.toString())) }
        schema.readText() shouldBe original
        command().parse(listOf("format", "--schema", schema.toString()))
        command().parse(listOf("format", "--check", "--schema", schema.toString()))
        schema.writeText(schema.readText().replace("String", "Strng"))
        shouldThrow<CliktError> { command().parse(listOf("validate", "--schema", schema.toString())) }
        shouldThrow<CliktError> { command().parse(listOf("generate", "--schema", schema.toString())) }
    }
}
