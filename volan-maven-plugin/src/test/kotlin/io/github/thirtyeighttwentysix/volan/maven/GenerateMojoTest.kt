package io.github.thirtyeighttwentysix.volan.maven

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.project.MavenProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

class GenerateMojoTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `generation registers the source root without connecting and semantic errors fail Maven`() {
        val source = directory.resolve("schema.volan")
        source.writeText(
            """
            datasource db { provider = "h2" url = env("UNSET_URL") }
            generator client { provider = "volan-kotlin" package = "example" }
            model User { id Int @id }
            """.trimIndent(),
        )
        val output = directory.resolve("generated")
        val mojo = GenerateMojo().apply {
            project = MavenProject()
            schemaFile = source.toFile()
            outputDirectory = output.toFile()
        }
        mojo.execute()
        mojo.project.compileSourceRoots shouldContain output.toString()
        output.resolve("example/User.kt").exists() shouldBe true
        source.writeText("model User { id NotAType @id }")
        shouldThrow<MojoExecutionException> { mojo.execute() }
        output.resolve("example/User.kt").exists() shouldBe true
    }
}
