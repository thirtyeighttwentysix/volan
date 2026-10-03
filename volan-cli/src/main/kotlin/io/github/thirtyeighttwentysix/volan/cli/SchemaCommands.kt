package io.github.thirtyeighttwentysix.volan.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.github.thirtyeighttwentysix.volan.codegen.GeneratedSources
import io.github.thirtyeighttwentysix.volan.codegen.VolanGenerationException
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.schema.SchemaFormatter
import io.github.thirtyeighttwentysix.volan.schema.VolanSchemaException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.readText

internal abstract class SchemaCommand(name: String) : CliktCommand(name = name) {
    protected val schemaPath: String by option("--schema", help = "Schema file.").default("schema.volan")

    protected fun checked(action: () -> Unit) {
        try {
            action()
        } catch (failure: VolanSchemaException) {
            cliError(failure)
        } catch (failure: VolanGenerationException) {
            cliError(failure)
        } catch (failure: IOException) {
            cliError(failure)
        } catch (failure: IllegalArgumentException) {
            cliError(failure)
        }
    }

    private fun cliError(failure: Exception): Nothing = throw CliktError(failure.message.orEmpty(), cause = failure)
}

internal class Generate : SchemaCommand("generate") {
    private val output: String? by option(
        "--output",
        help = "Output directory; defaults to the generator output, relative to this project.",
    )

    override fun run(): Unit = checked {
        val result = SchemaLoader.load(schemaPath, Path.of(schemaPath).readText())
        val schema = result.schemaOrThrow()
        if (result.warnings.isNotEmpty()) echo(result.render(), err = true)
        val generator = schema.generators.singleOrNull { it.provider == "volan-kotlin" }
            ?: throw CliktError("Expected exactly one generator with provider \"volan-kotlin\".")
        val destination = Path.of(output ?: generator.outputDirectory)
        val files = GeneratedSources.write(schema, destination)
        echo("Generated ${files.size} source files in $destination")
    }
}

internal class Validate : SchemaCommand("validate") {
    override fun run(): Unit = checked {
        val result = SchemaLoader.load(schemaPath, Path.of(schemaPath).readText())
        result.schemaOrThrow()
        if (result.warnings.isNotEmpty()) echo(result.render(), err = true)
        echo("Schema is valid: $schemaPath")
    }
}

internal class Format : SchemaCommand("format") {
    private val check: Boolean by option("--check", help = "Fail if formatting differs, without writing.").flag()
    private val stdout: Boolean by option("--stdout", help = "Print formatted schema without writing.").flag()

    override fun run(): Unit = checked {
        require(!(check && stdout)) { "--check and --stdout cannot be used together." }
        val path = Path.of(schemaPath)
        val original = path.readText()
        val formatted = SchemaFormatter.format(schemaPath, original)
        when {
            stdout -> echo(formatted, trailingNewline = false)
            check && original != formatted -> throw CliktError("Schema needs formatting: $schemaPath")
            check -> echo("Schema is formatted: $schemaPath")
            else -> {
                if (original != formatted) Files.writeString(path, formatted)
                echo("Formatted $schemaPath")
            }
        }
    }
}

internal class Init : SchemaCommand("init") {
    private val provider: String by option("--provider", help = "Database provider.")
        .choice("postgresql", "sqlite", "h2", "mysql", "mariadb").default("postgresql")
    private val packageName: String by option("--package", help = "Generated client package.").default("com.example.volan")
    private val javaFriendly: Boolean by option("--java-friendly", help = "Include Java callbacks and builders.").flag()

    override fun run(): Unit = checked {
        require(packageName.split('.').all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) { "Invalid package: $packageName" }
        val text = """
            datasource db {
              provider = "$provider"
              url = env("DATABASE_URL")
            }

            generator client {
              provider = "volan-kotlin"
              package = "$packageName"
              javaFriendly = $javaFriendly
            }

            model User {
              id Int @id @default(autoincrement())
              email String @unique
              name String?
            }
        """.trimIndent()
        val formatted = SchemaFormatter.format(schemaPath, text)
        SchemaLoader.load(schemaPath, formatted).schemaOrThrow()
        val path = Path.of(schemaPath)
        path.toAbsolutePath().parent.let { Files.createDirectories(it) }
        Files.writeString(path, formatted, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        echo("Created $schemaPath. Run volan generate, or add the Volan build plugin.")
    }
}
