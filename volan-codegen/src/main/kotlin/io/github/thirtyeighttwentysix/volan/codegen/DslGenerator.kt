package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import io.github.thirtyeighttwentysix.volan.ir.Cardinality
import io.github.thirtyeighttwentysix.volan.ir.FieldType
import io.github.thirtyeighttwentysix.volan.ir.Model
import io.github.thirtyeighttwentysix.volan.ir.RelationField
import io.github.thirtyeighttwentysix.volan.ir.ScalarField
import io.github.thirtyeighttwentysix.volan.ir.ScalarType

/**
 * Generates the blocks a query is written in: `where`, `orderBy`, `select` and `include`, plus the
 * scope that ties them together.
 *
 * Each is a class with one member per field, which is what makes the IDE able to complete a query and
 * the compiler able to reject a typo in it.
 */
internal class DslGenerator(private val types: TypeResolver) {
    fun whereScope(model: Model): TypeSpec {
        val scopeType = types.declared("${model.name}Where")
        val builder = TypeSpec.classBuilder("${model.name}Where")
            .addKdoc("Conditions on `${model.name}`. Conditions written one after another mean `AND`.\n")
            .superclass(Types.filterScope)

        model.fields.filter { it.cardinality != Cardinality.LIST }.forEach { field ->
            builder.addProperty(filterHandle(model, field))
        }
        listOf("or" to "recordAnyOf", "and" to "recordAllOf", "not" to "recordNoneOf").forEach { (name, hook) ->
            builder.addFunction(
                FunSpec.builder(name)
                    .addKdoc("Groups the conditions in [block] with `${name.uppercase()}`.\n")
                    .addParameter("block", lambdaOn(scopeType))
                    .addStatement("%L(%T().apply(block))", hook, scopeType)
                    .build(),
            )
        }
        model.relationFields.forEach { relation ->
            val filterType = types.declared(relationFilterName(model, relation))
            builder.addFunction(
                FunSpec.builder(relation.name)
                    .addKdoc("Conditions on the related `${relation.targetModel}` rows.\n")
                    .addParameter("block", lambdaOn(filterType))
                    .addStatement(
                        "%T { quantifier, nested -> recordRelated(%S, quantifier, nested) }.apply(block)",
                        filterType,
                        relation.name,
                    )
                    .build(),
            )
        }
        return builder.build()
    }

    private fun filterHandle(model: Model, field: ScalarField): PropertySpec {
        val element = types.elementType(field.type)
        val handle: Pair<TypeName, CodeBlock> = when (val fieldType = field.type) {
            is FieldType.EnumRef -> Types.enumFilterField.parameterizedBy(element) to
                CodeBlock.of("enumField(%S) { it.databaseValue }", field.dbName)
            is FieldType.Scalar -> when {
                fieldType.type == ScalarType.STRING ->
                    Types.textFilterField to CodeBlock.of("textField(%S)", field.dbName)
                Types.isOrdered(fieldType.type) ->
                    Types.orderedFilterField.parameterizedBy(element) to CodeBlock.of("orderedField(%S)", field.dbName)
                else ->
                    Types.equalityFilterField.parameterizedBy(element) to CodeBlock.of("equalityField(%S)", field.dbName)
            }
        }
        return PropertySpec.builder(field.name, handle.first)
            .addKdoc("Conditions on `${model.name}.${field.name}`.\n")
            .initializer(handle.second)
            .build()
    }

    fun relationFilter(model: Model, relation: RelationField): TypeSpec {
        val targetWhere = types.declared("${relation.targetModel}Where")
        val sink = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.unnamed(Types.relationQuantifier), ParameterSpec.unnamed(Types.filterScope)),
            returnType = UNIT,
        )
        val builder = TypeSpec.classBuilder(relationFilterName(model, relation))
            .addKdoc("How the related `${relation.targetModel}` rows have to match.\n")
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("sink", sink).build())
            .addProperty(PropertySpec.builder("sink", sink).addModifiers(KModifier.PRIVATE).initializer("sink").build())

        val quantifiers = if (relation.cardinality == Cardinality.LIST) {
            listOf("some" to "SOME", "every" to "EVERY", "none" to "NONE")
        } else {
            listOf("matches" to "IS", "notMatches" to "IS_NOT")
        }
        quantifiers.forEach { (name, constant) ->
            builder.addFunction(
                FunSpec.builder(name)
                    .addKdoc(quantifierDoc(name, relation))
                    .addParameter("block", lambdaOn(targetWhere))
                    .addStatement("sink(%T.%L, %T().apply(block))", Types.relationQuantifier, constant, targetWhere)
                    .build(),
            )
        }
        return builder.build()
    }

    private fun quantifierDoc(name: String, relation: RelationField): String = when (name) {
        "some" -> "Matches when at least one related `${relation.targetModel}` matches.\n"
        "every" -> "Matches when every related `${relation.targetModel}` matches.\n"
        "none" -> "Matches when no related `${relation.targetModel}` matches.\n"
        "matches" -> "Matches when the related `${relation.targetModel}` matches.\n"
        else -> "Matches when the related `${relation.targetModel}` does not match.\n"
    }

    fun orderScope(model: Model): TypeSpec {
        val builder = TypeSpec.classBuilder("${model.name}OrderBy")
            .addKdoc("How to sort `${model.name}`. Terms apply in the order they are written.\n")
            .superclass(Types.orderScope)
        model.fields.filter { it.cardinality != Cardinality.LIST }.forEach { field ->
            builder.addProperty(
                PropertySpec.builder(field.name, Types.orderField)
                    .addKdoc("Sorts on `${model.name}.${field.name}`.\n")
                    .initializer("orderField(%S)", field.dbName)
                    .build(),
            )
        }
        return builder.build()
    }

    fun selectScope(model: Model): TypeSpec {
        val builder = TypeSpec.classBuilder("${model.name}Select")
            .addKdoc("Which fields of `${model.name}` to read. Naming a field selects it.\n")
            .superclass(Types.selectScope)
        model.fields.forEach { field ->
            builder.addProperty(
                PropertySpec.builder(field.name, UNIT)
                    .addKdoc("Selects `${field.name}`.\n")
                    .getter(FunSpec.getterBuilder().addStatement("return markSelected(%S)", field.name).build())
                    .build(),
            )
        }
        return builder.build()
    }

    fun includeScope(model: Model): TypeSpec {
        val builder = TypeSpec.classBuilder("${model.name}Include")
            .addKdoc(
                "Which relations of `${model.name}` to load.\n\n" +
                    "Each one costs a single extra statement, whatever the size of the result.\n",
            )
            .superclass(Types.includeScope)
        model.relationFields.forEach { relation ->
            val nestedQuery = types.declared("${relation.targetModel}Query")
            builder.addFunction(
                FunSpec.builder(relation.name)
                    .addKdoc("Loads the related `${relation.targetModel}` rows.\n")
                    .addParameter(
                        ParameterSpec.builder("block", lambdaOn(nestedQuery)).defaultValue("{}").build(),
                    )
                    .addStatement("includeRelation(%S, %T().apply(block).build())", relation.name, nestedQuery)
                    .build(),
            )
        }
        return builder.build()
    }

    fun queryScope(model: Model): TypeSpec {
        val builder = TypeSpec.classBuilder("${model.name}Query")
            .addKdoc("A read of `${model.name}`: its filter, ordering, paging, projection and relations.\n")
            .superclass(Types.queryScope)
            .addSuperclassConstructorParameter("%S", model.name)
            .addProperty(
                PropertySpec.builder("selectedFields", SET.parameterizedBy(STRING).copy(nullable = true))
                    .addModifiers(KModifier.INTERNAL)
                    .mutable()
                    .initializer("null")
                    .build(),
            )
            .addFunction(scopeEntry("where", "${model.name}Where", "recordFilter"))
            .addFunction(scopeEntry("orderBy", "${model.name}OrderBy", "recordOrder"))
            .addFunction(scopeEntry("include", "${model.name}Include", "recordIncludes"))
            .addFunction(selectEntry(model))
            .addFunction(distinctEntry(model))
        cursorFunction(model)?.let { builder.addFunction(it) }
        return builder.build()
    }

    /** The fields of a model, for the blocks that name some of them without reading them. */
    fun fields(model: Model): TypeSpec {
        val builder = TypeSpec.classBuilder("${model.name}Fields")
            .addKdoc("The fields of `${model.name}`, for the blocks that name some of them.\n")
            .superclass(Types.selectScope)
        model.fields.forEach { field ->
            builder.addProperty(
                PropertySpec.builder(field.name, UNIT)
                    .addKdoc("Names `${field.name}`.\n")
                    .getter(FunSpec.getterBuilder().addStatement("return markSelected(%S)", field.name).build())
                    .build(),
            )
        }
        return builder.build()
    }

    private fun distinctEntry(model: Model): FunSpec = FunSpec.builder("distinct")
        .addKdoc(
            "Returns only rows that differ in the named fields.\n\n" +
                "With no fields named, nothing is de-duplicated: the query returns every matching row.\n",
        )
        .addParameter("block", lambdaOn(types.declared("${model.name}Fields")))
        .addStatement(
            "recordDistinct(%T().apply(block).build().map { requireNotNull(%T.METADATA.column(it)).column })",
            types.declared("${model.name}Fields"),
            types.declared("${model.name}Table"),
        )
        .build()

    private fun scopeEntry(name: String, scope: String, hook: String): FunSpec {
        val scopeType = types.declared(scope)
        return FunSpec.builder(name)
            .addKdoc("Applies the `$name` block.\n")
            .addParameter("block", lambdaOn(scopeType))
            .addStatement("%L(%T().apply(block))", hook, scopeType)
            .build()
    }

    private fun selectEntry(model: Model): FunSpec {
        val selectType = types.declared("${model.name}Select")
        val table = types.declared("${model.name}Table")
        return FunSpec.builder("select")
            .addKdoc(
                "Reads only the named fields.\n\n" +
                    "Use it with the `project…` operations, whose result refuses to hand back a field this " +
                    "block left out.\n",
            )
            .addParameter("block", lambdaOn(selectType))
            .addStatement("val fields = %T().apply(block).build()", selectType)
            .addStatement("selectedFields = fields")
            .addStatement(
                "recordSelection(fields.map { requireNotNull(%T.METADATA.column(it)).column })",
                table,
            )
            .build()
    }

    private fun cursorFunction(model: Model): FunSpec? {
        val key = model.primaryKey ?: return null
        val fields = key.fields.mapNotNull { model.field(it) }
        if (fields.isEmpty()) return null
        val builder = FunSpec.builder("cursor")
            .addKdoc(
                "Resumes after the row with this key.\n\n" +
                    "Cursor paging stays correct while rows are being inserted, which `skip` cannot promise.\n",
            )
        fields.forEach { builder.addParameter(it.name, types.fieldType(it.type, it.cardinality)) }
        builder.addParameter(ParameterSpec.builder("inclusive", BOOLEAN).defaultValue("false").build())
        val entries = fields.joinToString(", ") { "\"${it.dbName}\" to ${encodeExpression(it)}" }
        builder.addStatement("recordCursor(mapOf(%L), inclusive)", entries)
        return builder.build()
    }

    private fun encodeExpression(field: ScalarField): String =
        if (field.type is FieldType.EnumRef) "${field.name}.databaseValue" else field.name

    private fun relationFilterName(model: Model, relation: RelationField): String =
        "${model.name}${relation.name.replaceFirstChar { it.uppercase() }}Filter"

    private fun lambdaOn(receiver: TypeName): TypeName = LambdaTypeName.get(receiver = receiver, returnType = UNIT)
}
