package io.github.thirtyeighttwentysix.volan.gradle

import io.github.thirtyeighttwentysix.volan.codegen.GeneratedSources
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import java.util.Properties

/** Schema and output locations for the generated main client. */
public abstract class VolanExtension {
    /** Defaults to the module's `schema.volan`. */
    public abstract val schemaFile: RegularFileProperty

    /** Defaults to `build/generated/volan`; build plugins control output independently of the schema. */
    public abstract val outputDirectory: DirectoryProperty
}

/** Generates a client without connecting to the database or reading datasource environment variables. */
@CacheableTask
public abstract class VolanGenerateTask : DefaultTask() {
    /** The complete schema input. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val schemaFile: RegularFileProperty

    /** Generated Kotlin files and their ownership manifest. */
    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    /** Validates, generates and removes sources of deleted models. */
    @TaskAction
    public fun generate() {
        val input = schemaFile.get().asFile
        val result = SchemaLoader.load(input.name, input.readText())
        val schema = result.schemaOrThrow()
        if (result.warnings.isNotEmpty()) logger.warn(result.render())
        val files = GeneratedSources.write(schema, outputDirectory.get().asFile.toPath())
        logger.lifecycle("Generated ${files.size} Volan source files")
    }
}

/** Wires generation into Kotlin JVM compilation and adds the matching Volan runtime dependency. */
public class VolanPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("volan", VolanExtension::class.java)
        extension.schemaFile.convention(target.layout.projectDirectory.file("schema.volan"))
        extension.outputDirectory.convention(target.layout.buildDirectory.dir("generated/volan"))
        val generate = target.tasks.register("volanGenerate", VolanGenerateTask::class.java) { task ->
            task.group = "volan"
            task.description = "Generates the client from schema.volan."
            task.schemaFile.set(extension.schemaFile)
            task.outputDirectory.set(extension.outputDirectory)
        }
        target.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val sources = target.extensions.getByType(SourceSetContainer::class.java).getByName("main")
            val kotlin = sources.extensions.getByName("kotlin") as SourceDirectorySet
            kotlin.srcDir(generate.flatMap { it.outputDirectory })
            val properties = Properties().apply {
                VolanPlugin::class.java.getResourceAsStream("/volan-plugin.properties").use { load(it) }
            }
            target.dependencies.add("implementation", "io.github.thirtyeighttwentysix:volan-runtime:${properties.getProperty("version")}")
        }
    }
}
