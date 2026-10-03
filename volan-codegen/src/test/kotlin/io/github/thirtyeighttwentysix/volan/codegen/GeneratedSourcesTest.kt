package io.github.thirtyeighttwentysix.volan.codegen

import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GeneratedSourcesTest {
    @TempDir
    lateinit var output: Path

    private fun schema(model: String) = SchemaLoader.load(
        "schema.volan",
        """
        datasource db { provider = "h2" url = env("UNSET_URL") }
        generator client { provider = "volan-kotlin" package = "example" }
        model $model { id Int @id }
        """.trimIndent(),
    ).schemaOrThrow()

    @Test
    fun `deleted models and renamed packages remove owned files and retain unrelated files`() {
        val first = schema("Before")
        GeneratedSources.write(first, output)
        val unrelated = output.resolve("notes.txt").apply { writeText("keep") }
        val second = schema("After").let { it.copy(generators = it.generators.map { g -> g.copy(packageName = "other") }) }
        GeneratedSources.write(second, output)
        output.resolve("example/Before.kt").exists() shouldBe false
        output.resolve("example/VolanClient.kt").exists() shouldBe false
        output.resolve("other/After.kt").exists() shouldBe true
        unrelated.readText() shouldBe "keep"
    }

    @Test
    fun `bad schemas collisions and tampered manifests preserve existing sources`() {
        val first = schema("Before")
        GeneratedSources.write(first, output)
        val original = output.resolve("example/Before.kt").readText()
        shouldThrow<VolanGenerationException> { GeneratedSources.write(first.copy(generators = emptyList()), output) }
        output.resolve("example/After.kt").writeText("my code")
        shouldThrow<IllegalArgumentException> { GeneratedSources.write(schema("After"), output) }
        output.resolve("example/Before.kt").readText() shouldBe original
        output.resolve(".volan-generated-files").writeText("../../outside.kt\n")
        shouldThrow<IllegalArgumentException> { GeneratedSources.write(first, output) }
        output.resolve("example/Before.kt").readText() shouldBe original
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `directory aliases above output work but links inside generated output are refused`() {
        val actual = Files.createDirectory(output.resolve("actual"))
        val alias = Files.createSymbolicLink(output.resolve("alias"), actual)
        GeneratedSources.write(schema("Before"), alias.resolve("generated"))
        actual.resolve("generated/example/Before.kt").exists() shouldBe true
        val unsafe = Files.createDirectory(actual.resolve("unsafe"))
        Files.createSymbolicLink(unsafe.resolve("example"), output)
        shouldThrow<IllegalArgumentException> { GeneratedSources.write(schema("Before"), unsafe) }
        output.resolve("Before.kt").exists() shouldBe false
    }
}
