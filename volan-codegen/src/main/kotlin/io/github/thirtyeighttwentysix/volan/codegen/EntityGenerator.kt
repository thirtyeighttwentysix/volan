package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import io.github.thirtyeighttwentysix.volan.ir.Cardinality
import io.github.thirtyeighttwentysix.volan.ir.EnumType
import io.github.thirtyeighttwentysix.volan.ir.Model
import io.github.thirtyeighttwentysix.volan.ir.RelationField
import io.github.thirtyeighttwentysix.volan.ir.ScalarField

/**
 * Generates the types that hold data: enums and entities.
 *
 * An entity carries its scalar columns as ordinary properties and its relations in slots, so that a
 * relation the query did not load says so instead of reading as absent. Equality and `toString` cover
 * the scalars only: two rows with the same data are the same row whether or not someone asked for
 * their comments.
 */
internal class EntityGenerator(private val types: TypeResolver) {
    fun enumType(enumType: EnumType): TypeSpec {
        val builder = TypeSpec.enumBuilder(enumType.name)
            .addKdoc(documentation(enumType.documentation, "Values of the `${enumType.name}` enum."))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("databaseValue", STRING)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("databaseValue", STRING)
                    .addKdoc("The value stored in the database, which `@map` can make differ from the name.\n")
                    .initializer("databaseValue")
                    .build(),
            )
        enumType.values.forEach { value ->
            builder.addEnumConstant(
                value.name,
                TypeSpec.anonymousClassBuilder()
                    .addSuperclassConstructorParameter("%S", value.dbName)
                    .apply { value.documentation?.let { addKdoc("%L\n", it) } }
                    .build(),
            )
        }
        return builder.addType(enumCompanion(enumType)).build()
    }

    private fun enumCompanion(enumType: EnumType): TypeSpec {
        val fromDatabase = FunSpec.builder("fromDatabaseValue")
            .addKdoc(
                "Returns the value stored as [value].\n\n" +
                    "@throws io.github.thirtyeighttwentysix.volan.runtime.VolanMappingException " +
                    "if the database holds something this enum has no value for.\n",
            )
            .addAnnotation(ClassName("kotlin.jvm", "JvmStatic"))
            .addParameter("value", STRING)
            .returns(types.declared(enumType.name))
            .beginControlFlow("return when (value)")
            .apply {
                enumType.values.forEach { addStatement("%S -> %L", it.dbName, it.name) }
                addStatement(
                    "else -> throw %T(%P)",
                    Types.mappingException,
                    "`${enumType.name}` has no value stored as `\$value`. " +
                        "The database holds a value this schema does not know about.",
                )
            }
            .endControlFlow()
            .build()
        return TypeSpec.companionObjectBuilder().addFunction(fromDatabase).build()
    }

    fun entity(model: Model): TypeSpec {
        val entityName = types.declared(model.name)
        val constructor = FunSpec.constructorBuilder()
        val builder = TypeSpec.classBuilder(model.name)
            .addKdoc(documentation(model.documentation, "One row of `${model.dbName}`."))

        model.fields.forEach { field ->
            val type = types.fieldType(field.type, field.cardinality)
            constructor.addParameter(field.name, type)
            builder.addProperty(
                PropertySpec.builder(field.name, type)
                    .addKdoc(documentation(field.documentation, "The `${field.dbName}` column."))
                    .initializer(field.name)
                    .build(),
            )
        }
        model.relationFields.forEach { relation ->
            addRelation(builder, constructor, model, relation)
        }

        builder
            .primaryConstructor(constructor.build())
            .addFunction(equals(model, entityName))
            .addFunction(hashCode(model))
            .addFunction(toString(model))
        if (model.relationFields.isNotEmpty()) builder.addFunction(withRelationValue(model))
        return builder
            .addType(entityBuilder(model))
            .addType(entityCompanion(model))
            .build()
    }

    /**
     * Returns a copy carrying one loaded relation.
     *
     * It lives on the entity because only the entity can see its own slots, and it is internal because
     * only the generated mapper calls it — applications get relations from a query, not by hand.
     */
    private fun withRelationValue(model: Model): FunSpec {
        val body = CodeBlock.builder().beginControlFlow("return when (relation)")
        model.relationFields.forEach { relation ->
            val arguments = (
                model.fields.map { it.name } +
                    model.relationFields.map { other ->
                        if (other.name == relation.name) {
                            "${other.name}Slot = %T.loaded(value as %T)"
                        } else {
                            "${other.name}Slot = ${other.name}Slot"
                        }
                    }
                ).joinToString(", ")
            body.addStatement(
                "%S -> %T($arguments)",
                relation.name,
                types.declared(model.name),
                Types.relationSlot,
                relationType(relation),
            )
        }
        body.addStatement("else -> this")
        body.endControlFlow()
        return FunSpec.builder("withRelationValue")
            .addKdoc("Returns a copy of this row with `relation` loaded to `value`.\n")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("relation", STRING)
            .addParameter("value", ANY.copy(nullable = true))
            .returns(types.declared(model.name))
            .addCode(body.build())
            .build()
    }

    private fun addRelation(builder: TypeSpec.Builder, constructor: FunSpec.Builder, model: Model, relation: RelationField) {
        val loadedType = relationType(relation)
        val slotType = Types.relationSlot.parameterizedBy(loadedType)
        val slotName = "${relation.name}Slot"
        constructor.addParameter(
            ParameterSpec.builder(slotName, slotType)
                .defaultValue("%T.notLoaded()", Types.relationSlot)
                .build(),
        )
        builder.addProperty(PropertySpec.builder(slotName, slotType).addModifiers(KModifier.PRIVATE).initializer(slotName).build())
        builder.addProperty(
            PropertySpec.builder(relation.name, loadedType)
                .addKdoc(
                    documentation(
                        relation.documentation,
                        "The `${relation.targetModel}` rows this row relates to.",
                    ) + "\n@throws io.github.thirtyeighttwentysix.volan.runtime.VolanRelationNotLoadedException " +
                        "if the query did not `include` it.\n",
                )
                .getter(FunSpec.getterBuilder().addStatement("return %L.get(%S, %S)", slotName, model.name, relation.name).build())
                .build(),
        )
        builder.addProperty(
            PropertySpec.builder("${relation.name}IfLoaded", loadedType.copy(nullable = true))
                .addKdoc("The same rows, or `null` when the query did not load them.\n")
                .getter(FunSpec.getterBuilder().addStatement("return %L.orNull()", slotName).build())
                .build(),
        )
        builder.addProperty(
            PropertySpec.builder("is${relation.name.replaceFirstChar { it.uppercase() }}Loaded", BOOLEAN)
                .addKdoc("Whether the query loaded `${relation.name}`.\n")
                .getter(FunSpec.getterBuilder().addStatement("return %L.isLoaded", slotName).build())
                .build(),
        )
    }

    /** The type a loaded relation holds: a list on the many side, a possibly absent row on the other. */
    private fun relationType(relation: RelationField): TypeName {
        val target = types.declared(relation.targetModel)
        return when (relation.cardinality) {
            Cardinality.LIST -> LIST.parameterizedBy(target)
            Cardinality.OPTIONAL -> target.copy(nullable = true)
            Cardinality.REQUIRED -> target
        }
    }

    private fun equals(model: Model, entityName: ClassName): FunSpec {
        val comparisons = model.fields.joinToString(" &&\n    ") { field ->
            if (isByteArray(field)) "${field.name}.contentEquals(other.${field.name})" else "${field.name} == other.${field.name}"
        }
        return FunSpec.builder("equals")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("other", ANY.copy(nullable = true))
            .returns(BOOLEAN)
            .addStatement("if (this === other) return true")
            .addStatement("if (other !is %T) return false", entityName)
            .addCode(CodeBlock.of("return %L\n", comparisons))
            .build()
    }

    private fun hashCode(model: Model): FunSpec {
        val body = CodeBlock.builder()
        model.fields.forEachIndexed { index, field ->
            val expression = hashExpression(field)
            if (index == 0) body.addStatement("var result = %L", expression) else body.addStatement("result = 31 * result + %L", expression)
        }
        if (model.fields.isEmpty()) body.addStatement("var result = 0")
        body.addStatement("return result")
        return FunSpec.builder("hashCode").addModifiers(KModifier.OVERRIDE).returns(INT).addCode(body.build()).build()
    }

    private fun hashExpression(field: ScalarField): String {
        val nullable = field.cardinality == Cardinality.OPTIONAL
        val call = if (isByteArray(field)) "contentHashCode()" else "hashCode()"
        return if (nullable) "(${field.name}?.$call ?: 0)" else "${field.name}.$call"
    }

    private fun toString(model: Model): FunSpec {
        val parts = model.fields.joinToString(", ") { field ->
            if (isByteArray(field)) "${field.name}=\${${field.name}.contentToString()}" else "${field.name}=\$${field.name}"
        }
        return FunSpec.builder("toString")
            .addModifiers(KModifier.OVERRIDE)
            .returns(STRING)
            .addStatement("return %P", "${model.name}($parts)")
            .build()
    }

    private fun isByteArray(field: ScalarField): Boolean = types.fieldType(field.type, Cardinality.REQUIRED) == BYTE_ARRAY

    /** A fluent builder, which is how Java constructs an entity and how Kotlin copies one with changes. */
    private fun entityBuilder(model: Model): TypeSpec {
        val builderType = types.declared(model.name).nestedClass("Builder")
        val builder = TypeSpec.classBuilder("Builder")
            .addKdoc("Builds a [%T] field by field.\n", types.declared(model.name))
        model.fields.forEach { field ->
            val type = types.fieldType(field.type, field.cardinality)
            builder.addProperty(
                PropertySpec.builder("${field.name}Value", type.copy(nullable = true))
                    .addModifiers(KModifier.PRIVATE)
                    .mutable()
                    .initializer("null")
                    .build(),
            )
            builder.addFunction(
                FunSpec.builder(field.name)
                    .addKdoc("Sets `${field.name}`.\n")
                    .addParameter("value", type)
                    .returns(builderType)
                    .addStatement("this.%LValue = value", field.name)
                    .addStatement("return this")
                    .build(),
            )
        }
        return builder.addFunction(buildFunction(model)).build()
    }

    private fun buildFunction(model: Model): FunSpec {
        val body = CodeBlock.builder()
        body.add("return %T(\n", types.declared(model.name))
        model.fields.forEach { field ->
            if (field.cardinality == Cardinality.OPTIONAL) {
                body.add("  %L = %LValue,\n", field.name, field.name)
            } else {
                body.add(
                    "  %L = requireNotNull(%LValue) { %S },\n",
                    field.name,
                    field.name,
                    "${model.name}.${field.name} is required and was not set",
                )
            }
        }
        body.add(")\n")
        return FunSpec.builder("build")
            .addKdoc("Builds the entity, failing when a required field was never set.\n")
            .returns(types.declared(model.name))
            .addCode(body.build())
            .build()
    }

    private fun entityCompanion(model: Model): TypeSpec = TypeSpec.companionObjectBuilder()
        .addFunction(
            FunSpec.builder("builder")
                .addKdoc("Starts building a [%T].\n", types.declared(model.name))
                .addAnnotation(ClassName("kotlin.jvm", "JvmStatic"))
                .returns(types.declared(model.name).nestedClass("Builder"))
                .addStatement("return Builder()")
                .build(),
        )
        .build()

    private fun documentation(documentation: String?, fallback: String): String = (documentation ?: fallback).trimEnd() + "\n"
}
