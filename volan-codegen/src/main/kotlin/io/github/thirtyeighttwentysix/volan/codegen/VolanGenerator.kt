package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.FileSpec
import io.github.thirtyeighttwentysix.volan.VolanException
import io.github.thirtyeighttwentysix.volan.ir.EnumType
import io.github.thirtyeighttwentysix.volan.ir.GeneratorConfig
import io.github.thirtyeighttwentysix.volan.ir.Model
import io.github.thirtyeighttwentysix.volan.ir.Schema
import java.nio.file.Files
import java.nio.file.Path

/** Thrown when a schema cannot be generated from, for a reason validation could not have caught. */
public class VolanGenerationException(message: String) : VolanException(message)

/**
 * One generated source file.
 *
 * @property relativePath where it belongs under the output directory, using `/` on every platform.
 * @property contents the Kotlin source.
 */
public class GeneratedFile(public val relativePath: String, public val contents: String) {
    override fun toString(): String = relativePath
}

/**
 * Turns a validated [Schema] into the sources of a type-safe client.
 *
 * ```kotlin
 * val schema = SchemaLoader.loadOrThrow(SourceFile("schema.volan", text))
 * VolanGenerator.writeTo(schema, Path.of("build/generated/volan"))
 * ```
 *
 * Everything is decided here, at build time: the shape of the entities, the columns each mapper reads,
 * the members of each DSL. Nothing is discovered at run time, which is what keeps reflection off the
 * mapping path and lets the compiler catch a renamed field at every use site.
 *
 * Models and fields marked `@ignore` are left out entirely: Volan generates no client for something it
 * was told not to manage.
 */
public object VolanGenerator {
    /**
     * Generates the client described by the schema's `volan-kotlin` generator block.
     *
     * @throws VolanGenerationException if the schema declares no such generator, or more than one.
     */
    @JvmStatic
    public fun generate(schema: Schema): List<GeneratedFile> {
        val generators = schema.generators.filter { it.provider == KOTLIN_GENERATOR }
        if (generators.size != 1) {
            throw VolanGenerationException(
                "expected exactly one `generator` block with provider \"$KOTLIN_GENERATOR`\", found ${generators.size}",
            )
        }
        return generate(schema, generators.single())
    }

    /** Generates the client described by [generator]. */
    @JvmStatic
    public fun generate(schema: Schema, generator: GeneratorConfig): List<GeneratedFile> {
        val visible = visibleSchema(schema)
        val generators = Generators(visible, TypeResolver(generator.packageName))

        val files = ArrayList<GeneratedFile>()
        visible.enums.forEach { files.add(file(generator, it.name, enumFile(generator, generators.entities, it))) }
        visible.models.forEach { model ->
            files.add(file(generator, model.name, modelFile(generator, model, generators)))
        }
        files.add(
            file(
                generator,
                "VolanClient",
                FileSpec.builder(generator.packageName, "VolanClient")
                    .addFileComment(HEADER)
                    .addType(generators.repositories.client(visible, visible.models))
                    .build(),
            ),
        )
        return files
    }

    /**
     * Generates the client and writes it under [directory], creating the package directories.
     *
     * Returns the paths written, so a build task can declare them as its outputs.
     */
    @JvmStatic
    public fun writeTo(schema: Schema, directory: Path): List<Path> = generate(schema).map { generated ->
        val target = directory.resolve(generated.relativePath)
        Files.createDirectories(target.parent)
        Files.writeString(target, generated.contents)
        target
    }

    private fun enumFile(generator: GeneratorConfig, entities: EntityGenerator, enumType: EnumType): FileSpec =
        FileSpec.builder(generator.packageName, enumType.name)
            .addFileComment(HEADER)
            .addType(entities.enumType(enumType))
            .build()

    private fun modelFile(generator: GeneratorConfig, model: Model, generators: Generators): FileSpec {
        val builder = FileSpec.builder(generator.packageName, model.name)
            .addFileComment(HEADER)
            .addType(generators.entities.entity(model))
            .addType(generators.mapping.tableMetadata(model))
            .addType(generators.mapping.rowMapper(model))
            .addType(generators.mapping.projection(model))
            .addType(generators.mapping.projectionMapper(model))
            .addType(generators.dsl.whereScope(model))
            .addType(generators.dsl.orderScope(model))
            .addType(generators.dsl.selectScope(model))
            .addType(generators.dsl.includeScope(model))
            .addType(generators.dsl.queryScope(model))
            .addTypes(generators.aggregates.fieldScopes(model))
            .addType(generators.aggregates.scope(model))
            .addType(generators.aggregates.result(model))
            .addType(generators.groups.fields(model))
            .addType(generators.groups.having(model))
            .addType(generators.groups.scope(model))
            .addType(generators.groups.result(model))
            .addType(generators.writes.createData(model))
            .addType(generators.writes.updateData(model))
            .addType(generators.writes.createManyScope(model))
            .addType(generators.writes.updateScope(model))
            .addType(generators.writes.upsertScope(model))
            .addType(generators.writes.deleteScope(model))
            .addType(generators.repositories.repository(model))
        model.relationFields.forEach { relation ->
            builder.addType(generators.dsl.relationFilter(model, relation))
            builder.addType(generators.writes.nestedWriteScope(model, relation))
        }
        return builder.build()
    }

    /** The generators that make up one client, held together so that adding one is one edit. */
    private class Generators(schema: Schema, types: TypeResolver) {
        val entities: EntityGenerator = EntityGenerator(types)
        val mapping: MappingGenerator = MappingGenerator(schema, types)
        val dsl: DslGenerator = DslGenerator(types)
        val writes: WriteGenerator = WriteGenerator(schema, types)
        val aggregates: AggregateGenerator = AggregateGenerator(types)
        val groups: GroupGenerator = GroupGenerator(types, aggregates)
        val repositories: RepositoryGenerator = RepositoryGenerator(types)
    }

    private fun file(generator: GeneratorConfig, name: String, spec: FileSpec): GeneratedFile = GeneratedFile(
        relativePath = generator.packageName.replace('.', '/') + "/$name.kt",
        contents = spec.toString(),
    )

    /**
     * Drops everything marked `@ignore`, along with any relation that pointed at it.
     *
     * Generating a repository for a model the schema asked Volan to leave alone would be worse than
     * useless: it would offer an API for a table nobody promised is safe to write.
     */
    private fun visibleSchema(schema: Schema): Schema {
        val kept = schema.models.filterNot { it.isIgnored }.map { it.name }.toSet()
        val models = schema.models
            .filterNot { it.isIgnored }
            .map { model ->
                model.copy(
                    fields = model.fields.filterNot { it.isIgnored },
                    relationFields = model.relationFields.filterNot { it.isIgnored || it.targetModel !in kept },
                )
            }
        val relations = schema.relations.filter { it.from.model in kept && it.to.model in kept }
        return schema.copy(models = models, relations = relations)
    }

    private const val KOTLIN_GENERATOR = "volan-kotlin"
    private const val HEADER = "Generated by Volan from schema.volan. Do not edit: regenerate instead."
}
