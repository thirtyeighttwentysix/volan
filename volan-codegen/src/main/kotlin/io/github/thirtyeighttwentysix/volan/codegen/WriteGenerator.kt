package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MUTABLE_LIST
import com.squareup.kotlinpoet.MUTABLE_SET
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import io.github.thirtyeighttwentysix.volan.ir.Cardinality
import io.github.thirtyeighttwentysix.volan.ir.FieldType
import io.github.thirtyeighttwentysix.volan.ir.Model
import io.github.thirtyeighttwentysix.volan.ir.ScalarField

/**
 * Generates the blocks a write is described in.
 *
 * Both payloads track which fields were touched rather than reading the properties, so that setting a
 * field to null is a different thing from never mentioning it — the difference between writing `NULL`
 * and leaving a column alone.
 */
internal class WriteGenerator(private val types: TypeResolver) {
    fun createData(model: Model): TypeSpec = payload(
        model = model,
        name = "${model.name}CreateData",
        kdoc = "The values to insert as a new `${model.name}`.\n\n" +
            "A field with a default may be left unset; every other field has to be given a value.\n",
        requireMandatory = true,
    )

    fun updateData(model: Model): TypeSpec = payload(
        model = model,
        name = "${model.name}UpdateData",
        kdoc = "The values to change on a `${model.name}`.\n\n" +
            "Only the fields this block mentions are written; everything else is left alone.\n",
        requireMandatory = false,
    )

    private fun payload(model: Model, name: String, kdoc: String, requireMandatory: Boolean): TypeSpec {
        val builder = TypeSpec.classBuilder(name)
            .addKdoc(kdoc)
            .addProperty(
                PropertySpec.builder("touched", MUTABLE_SET.parameterizedBy(STRING))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("linkedSetOf()")
                    .build(),
            )
        model.fields.filterNot { it.isUpdatedAt }.forEach { field ->
            builder.addProperty(mutableField(model, field))
        }
        return builder.addFunction(toValues(model, requireMandatory)).build()
    }

    private fun mutableField(model: Model, field: ScalarField): PropertySpec {
        val type = types.fieldType(field.type, field.cardinality).copy(nullable = true)
        return PropertySpec.builder(field.name, type)
            .addKdoc(
                "The value for `${model.name}.${field.name}`.\n\n" +
                    "It is nullable here because it starts out unset; " +
                    (
                        if (field.cardinality ==
                            Cardinality.OPTIONAL
                        ) {
                            "setting it to null writes null."
                        } else {
                            "leaving it unset is what null means."
                        }
                        ) +
                    "\n",
            )
            .mutable()
            .initializer("null")
            .setter(
                FunSpec.setterBuilder()
                    .addParameter("value", type)
                    .addStatement("field = value")
                    .addStatement("touched.add(%S)", field.name)
                    .build(),
            )
            .build()
    }

    private fun toValues(model: Model, requireMandatory: Boolean): FunSpec {
        val body = CodeBlock.builder()
            .addStatement("val values = LinkedHashMap<%T, Any?>()", STRING)
        model.fields.filterNot { it.isUpdatedAt }.forEach { field ->
            body.beginControlFlow("if (touched.contains(%S))", field.name)
            body.addStatement("values[%S] = %L", field.dbName, encode(field, access(model, field)))
            if (requireMandatory && field.cardinality == Cardinality.REQUIRED && field.default == null) {
                body.nextControlFlow("else")
                body.addStatement(
                    "throw %T(%S)",
                    Types.validationException,
                    "${model.name}.${field.name} is required and was not set",
                )
            }
            body.endControlFlow()
        }
        body.addStatement("return values")
        return FunSpec.builder("toValues")
            .addModifiers(KModifier.INTERNAL)
            .addKdoc("The values to write, keyed by column name.\n")
            .returns(Types.valueMap)
            .addCode(body.build())
            .build()
    }

    /**
     * Reads the property, refusing null for a column that cannot hold it.
     *
     * The property is nullable because it starts out unset, so a required field being null here means
     * the caller wrote `field = null` on a column the schema says is not nullable.
     */
    private fun access(model: Model, field: ScalarField): CodeBlock = if (field.cardinality == Cardinality.OPTIONAL) {
        CodeBlock.of("%L", field.name)
    } else {
        CodeBlock.of(
            "(%L ?: throw %T(%S))",
            field.name,
            Types.validationException,
            "${model.name}.${field.name} cannot be set to null",
        )
    }

    private fun encode(field: ScalarField, expression: CodeBlock): CodeBlock = when (field.type) {
        is FieldType.EnumRef -> when (field.cardinality) {
            Cardinality.LIST -> CodeBlock.of("%L.map { it.databaseValue }", expression)
            Cardinality.OPTIONAL -> CodeBlock.of("%L?.databaseValue", expression)
            Cardinality.REQUIRED -> CodeBlock.of("%L.databaseValue", expression)
        }
        is FieldType.Scalar -> expression
    }

    /** `createMany { row { … } }`: a list of payloads described one block at a time. */
    fun createManyScope(model: Model): TypeSpec {
        val dataType = types.declared("${model.name}CreateData")
        val specList = MUTABLE_LIST.parameterizedBy(Types.createSpec)
        return TypeSpec.classBuilder("${model.name}CreateMany")
            .addKdoc("Several `${model.name}` rows to insert in one statement.\n")
            .addProperty(
                PropertySpec.builder("rows", specList)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("mutableListOf()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("row")
                    .addKdoc("Adds one row to insert.\n")
                    .addParameter("block", lambdaOn(dataType))
                    .addStatement("rows.add(%T(%S, %T().apply(block).toValues()))", Types.createSpec, model.name, dataType)
                    .build(),
            )
            .build()
    }

    /** `update { where { … }; data { … } }`. */
    fun updateScope(model: Model): TypeSpec = TypeSpec.classBuilder("${model.name}UpdateScope")
        .addKdoc("Which `${model.name}` rows to change, and what to change on them.\n")
        .addProperty(scopeProperty("filter", types.declared("${model.name}Where")))
        .addProperty(scopeProperty("payload", types.declared("${model.name}UpdateData")))
        .addFunction(scopeEntry("where", "filter", types.declared("${model.name}Where"), "Which rows to change."))
        .addFunction(scopeEntry("data", "payload", types.declared("${model.name}UpdateData"), "What to change."))
        .build()

    /** `upsert { where { … }; create { … }; update { … } }`. */
    fun upsertScope(model: Model): TypeSpec = TypeSpec.classBuilder("${model.name}UpsertScope")
        .addKdoc("Which `${model.name}` row to look for, what to insert when it is missing, what to change when it is not.\n")
        .addProperty(scopeProperty("filter", types.declared("${model.name}Where")))
        .addProperty(scopeProperty("insert", types.declared("${model.name}CreateData")))
        .addProperty(scopeProperty("patch", types.declared("${model.name}UpdateData")))
        .addFunction(scopeEntry("where", "filter", types.declared("${model.name}Where"), "Which row to look for."))
        .addFunction(scopeEntry("create", "insert", types.declared("${model.name}CreateData"), "What to insert when there is none."))
        .addFunction(scopeEntry("update", "patch", types.declared("${model.name}UpdateData"), "What to change when there is one."))
        .build()

    /** `delete { where { … } }`. */
    fun deleteScope(model: Model): TypeSpec = TypeSpec.classBuilder("${model.name}DeleteScope")
        .addKdoc("Which `${model.name}` rows to delete.\n")
        .addProperty(scopeProperty("filter", types.declared("${model.name}Where")))
        .addFunction(scopeEntry("where", "filter", types.declared("${model.name}Where"), "Which rows to delete."))
        .build()

    private fun scopeProperty(name: String, type: TypeName): PropertySpec =
        PropertySpec.builder(name, type).addModifiers(KModifier.INTERNAL).initializer("%T()", type).build()

    private fun scopeEntry(name: String, target: String, type: TypeName, kdoc: String): FunSpec = FunSpec.builder(name)
        .addKdoc("%L\n", kdoc)
        .addParameter("block", lambdaOn(type))
        .addStatement("%L.apply(block)", target)
        .build()

    private fun lambdaOn(receiver: TypeName): TypeName = LambdaTypeName.get(receiver = receiver, returnType = UNIT)
}
