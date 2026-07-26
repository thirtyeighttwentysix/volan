package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import io.github.thirtyeighttwentysix.volan.ir.Cardinality
import io.github.thirtyeighttwentysix.volan.ir.FieldType
import io.github.thirtyeighttwentysix.volan.ir.Model
import io.github.thirtyeighttwentysix.volan.ir.Relation
import io.github.thirtyeighttwentysix.volan.ir.ScalarField
import io.github.thirtyeighttwentysix.volan.ir.Schema

/**
 * Generates what turns rows into objects: the table metadata, the row mapper and the projection a
 * partial `select` produces.
 *
 * A mapper is straight-line code — one read per column, in order. That is the whole reason Volan can
 * promise no reflection on the mapping path.
 */
internal class MappingGenerator(private val schema: Schema, private val types: TypeResolver) {
    fun tableMetadata(model: Model): TypeSpec {
        val columns = CodeBlock.builder().add("listOf(\n")
        model.fields.forEach { field ->
            columns.add(
                "  %T(field = %S, column = %S, isNullable = %L, isGenerated = %L, isUpdatedAt = %L),\n",
                Types.columnMetadata,
                field.name,
                field.dbName,
                field.cardinality == Cardinality.OPTIONAL,
                field.default != null,
                field.isUpdatedAt,
            )
        }
        columns.add(")")

        val relations = CodeBlock.builder().add("listOf(\n")
        model.relationFields.forEach { field ->
            val relation = schema.relations.first { it.name == field.relationName }
            relations.add(
                "  %T(field = %S, target = %S, isList = %L, foreignKeyColumns = %L, referencedColumns = %L, joinTable = %L),\n",
                Types.relationMetadata,
                field.name,
                field.targetModel,
                field.cardinality == Cardinality.LIST,
                stringList(columnsOf(relation.from.model, relation.foreignKeyFields)),
                stringList(columnsOf(relation.to.model, relation.referencedFields)),
                relation.joinTable?.let { "\"$it\"" } ?: "null",
            )
        }
        relations.add(")")

        val metadata = PropertySpec.builder("METADATA", Types.tableMetadata)
            .addKdoc("What the runtime knows about `${model.dbName}`.\n")
            .addAnnotation(com.squareup.kotlinpoet.ClassName("kotlin.jvm", "JvmField"))
            .initializer(
                CodeBlock.builder()
                    .add("%T(\n", Types.tableMetadata)
                    .add("  model = %S,\n", model.name)
                    .add("  table = %S,\n", model.dbName)
                    .add("  columns = %L,\n", columns.build())
                    .add("  primaryKey = %L,\n", stringList(model.primaryKey?.fields.orEmpty().map { columnOf(model, it) }))
                    .add("  relations = %L,\n", relations.build())
                    .add(")")
                    .build(),
            )
            .build()

        val builder = TypeSpec.objectBuilder("${model.name}Table")
            .addKdoc("Column names and metadata for `${model.dbName}`, as constants.\n")
            .addProperty(metadata)
        model.fields.forEach { field ->
            builder.addProperty(
                PropertySpec.builder(constantName(field.name), STRING)
                    .addKdoc("The `${field.dbName}` column.\n")
                    .addAnnotation(com.squareup.kotlinpoet.ClassName("kotlin.jvm", "JvmField"))
                    .initializer("%S", field.dbName)
                    .build(),
            )
        }
        return builder.build()
    }

    fun rowMapper(model: Model): TypeSpec {
        val body = CodeBlock.builder().add("return %T(\n", types.declared(model.name))
        model.fields.forEach { field ->
            body.add("  %L = %L,\n", field.name, readExpression(field))
        }
        body.add(")\n")
        return TypeSpec.objectBuilder("${model.name}RowMapper")
            .addKdoc("Reads one row of `${model.dbName}` into a [%T].\n", types.declared(model.name))
            .addSuperinterface(Types.rowMapper.parameterizedBy(types.declared(model.name)))
            .addFunction(
                FunSpec.builder("map")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("row", Types.row)
                    .returns(types.declared(model.name))
                    .addCode(body.build())
                    .build(),
            )
            .build()
    }

    fun projection(model: Model): TypeSpec {
        val builder = TypeSpec.classBuilder("${model.name}Projection")
            .addKdoc(
                "The result of a partial `select` on `${model.name}`.\n\n" +
                    "Fields the query did not select refuse to be read, naming the `select` to change, " +
                    "rather than reading as null.\n",
            )
        val constructor = FunSpec.constructorBuilder()
            .addParameter("selected", Types.selectedFields)
        builder.addProperty(
            PropertySpec.builder("selected", Types.selectedFields).addModifiers(KModifier.PRIVATE).initializer("selected").build(),
        )

        model.fields.forEach { field ->
            val type = types.fieldType(field.type, field.cardinality)
            val nullable = type.copy(nullable = true)
            constructor.addParameter(
                com.squareup.kotlinpoet.ParameterSpec.builder("${field.name}Value", nullable).defaultValue("null").build(),
            )
            builder.addProperty(
                PropertySpec.builder(
                    "${field.name}Value",
                    nullable,
                ).addModifiers(KModifier.PRIVATE).initializer("${field.name}Value").build(),
            )
            builder.addProperty(
                PropertySpec.builder(field.name, type)
                    .addKdoc(
                        "The `${field.dbName}` column.\n\n" +
                            "@throws io.github.thirtyeighttwentysix.volan.runtime.VolanFieldNotSelectedException " +
                            "if the query did not select it.\n",
                    )
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return selected.require<%T>(%S, %S, %LValue)", type, model.name, field.name, field.name)
                            .build(),
                    )
                    .build(),
            )
            builder.addProperty(
                PropertySpec.builder("is${field.name.replaceFirstChar { it.uppercase() }}Selected", BOOLEAN)
                    .addKdoc("Whether the query selected `${field.name}`.\n")
                    .getter(FunSpec.getterBuilder().addStatement("return selected.contains(%S)", field.name).build())
                    .build(),
            )
        }
        return builder.primaryConstructor(constructor.build()).build()
    }

    fun projectionMapper(model: Model): TypeSpec {
        val body = CodeBlock.builder()
            .addStatement("val selection = %T.of(fields)", Types.selectedFields)
            .add("return %T(\n", types.declared("${model.name}Projection"))
            .add("  selected = selection,\n")
        model.fields.forEach { field ->
            body.add("  %LValue = if (fields.contains(%S)) %L else null,\n", field.name, field.name, readExpression(field))
        }
        body.add(")\n")

        return TypeSpec.classBuilder("${model.name}ProjectionMapper")
            .addKdoc("Reads the columns a `select` asked for into a [%T].\n", types.declared("${model.name}Projection"))
            .addSuperinterface(Types.rowMapper.parameterizedBy(types.declared("${model.name}Projection")))
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("fields", SET.parameterizedBy(STRING)).build())
            .addProperty(
                PropertySpec.builder("fields", SET.parameterizedBy(STRING)).addModifiers(KModifier.PRIVATE).initializer("fields").build(),
            )
            .addFunction(
                FunSpec.builder("map")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("row", Types.row)
                    .returns(types.declared("${model.name}Projection"))
                    .addCode(body.build())
                    .build(),
            )
            .build()
    }

    /** The expression that reads one column out of a [io.github.thirtyeighttwentysix.volan.runtime.Row]. */
    private fun readExpression(field: ScalarField): CodeBlock {
        val optional = field.cardinality == Cardinality.OPTIONAL
        if (field.cardinality == Cardinality.LIST) return listReadExpression(field)
        return when (val type = field.type) {
            is FieldType.Scalar -> {
                val accessor = Types.rowAccessor(type.type) + if (optional) "OrNull" else ""
                CodeBlock.of("row.%L(%S)", accessor, field.dbName)
            }
            is FieldType.EnumRef -> if (optional) {
                CodeBlock.of("row.getStringOrNull(%S)?.let { %T.fromDatabaseValue(it) }", field.dbName, types.declared(type.enumName))
            } else {
                CodeBlock.of("%T.fromDatabaseValue(row.getString(%S))", types.declared(type.enumName), field.dbName)
            }
        }
    }

    private fun listReadExpression(field: ScalarField): CodeBlock {
        val element = types.elementType(field.type)
        return when (val type = field.type) {
            is FieldType.Scalar -> CodeBlock.of("row.getScalarList(%S).map { it as %T }", field.dbName, element)
            is FieldType.EnumRef -> CodeBlock.of(
                "row.getScalarList(%S).map { %T.fromDatabaseValue(it as %T) }",
                field.dbName,
                types.declared(type.enumName),
                STRING,
            )
        }
    }

    private fun columnsOf(modelName: String, fields: List<String>): List<String> {
        val model = schema.model(modelName) ?: return fields
        return fields.map { columnOf(model, it) }
    }

    private fun columnOf(model: Model, field: String): String = model.field(field)?.dbName ?: field

    private fun stringList(values: List<String>): String =
        if (values.isEmpty()) "emptyList()" else values.joinToString(", ", "listOf(", ")") { "\"$it\"" }

    private fun constantName(field: String): String = field.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()
}

/** The type a list of [T] takes in generated code. */
internal fun listOfType(type: TypeName): TypeName = LIST.parameterizedBy(type)
