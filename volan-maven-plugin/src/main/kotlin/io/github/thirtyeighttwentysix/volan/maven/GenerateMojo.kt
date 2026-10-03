package io.github.thirtyeighttwentysix.volan.maven

import io.github.thirtyeighttwentysix.volan.codegen.GeneratedSources
import io.github.thirtyeighttwentysix.volan.codegen.VolanGenerationException
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.schema.VolanSchemaException
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.project.MavenProject
import java.io.File
import java.io.IOException

/** Generates and registers Kotlin sources before Maven compilation. */
public class GenerateMojo : AbstractMojo() {
    /** Injected by Maven; the current module. */
    public lateinit var project: MavenProject

    /** Defaults to `${project.basedir}/schema.volan`. */
    public lateinit var schemaFile: File

    /** Defaults to `${project.build.directory}/generated-sources/volan`. */
    public lateinit var outputDirectory: File

    override fun execute() {
        try {
            val result = SchemaLoader.load(schemaFile.name, schemaFile.readText())
            val schema = result.schemaOrThrow()
            if (result.warnings.isNotEmpty()) log.warn(result.render())
            val files = GeneratedSources.write(schema, outputDirectory.toPath())
            project.addCompileSourceRoot(outputDirectory.absolutePath)
            log.info("Generated ${files.size} Volan source files")
        } catch (failure: VolanSchemaException) {
            generationError(failure)
        } catch (failure: VolanGenerationException) {
            generationError(failure)
        } catch (failure: IOException) {
            generationError(failure)
        } catch (failure: IllegalArgumentException) {
            generationError(failure)
        }
    }

    private fun generationError(failure: Exception): Nothing = throw MojoExecutionException(failure.message, failure)
}
