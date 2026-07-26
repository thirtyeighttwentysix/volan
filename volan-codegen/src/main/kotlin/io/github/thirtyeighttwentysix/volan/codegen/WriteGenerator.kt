package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
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
import io.github.thirtyeighttwentysix.volan.ir.RelationField
import io.github.thirtyeighttwentysix.volan.ir.ScalarField
import io.github.thirtyeighttwentysix.volan.ir.Schema

/**
 * Generates the blocks a write is described in.
 *
 * Both payloads track which fields were touched rather than reading the properties, so that setting a
 * field to null is a different thing from never mentioning it — the difference between writing `NULL`
 * and leaving a column alone.
 */
internal class WriteGenerator(private val schema: Schema, private val types: TypeResolver) {
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
        if (requireMandatory) addNestedWrites(builder, model)
        return builder.addFunction(toValues(model)).build()
    }

    /**
     * Adds one block per relation, through which a create can write the rows on the far side of it.
     *
     * They share one list, so the order the caller wrote them in is the order the runtime sees.
     */
    private fun addNestedWrites(builder: TypeSpec.Builder, model: Model) {
        if (model.relationFields.isEmpty()) return
        builder.addProperty(
            PropertySpec.builder("writes", MUTABLE_LIST.parameterizedBy(Types.nestedWrite))
                .addModifiers(KModifier.PRIVATE)
                .initializer("mutableListOf()")
                .build(),
        )
        model.relationFields.forEach { relation ->
            builder.addProperty(
                PropertySpec.builder(relation.name, types.declared(nestedWriteName(model, relation)))
                    .addKdoc("Rows of `${relation.targetModel}` to write with this one.\n")
                    .initializer("%T(writes)", types.declared(nestedWriteName(model, relation)))
                    .build(),
            )
        }
        builder.addFunction(
            FunSpec.builder("toNested")
                .addKdoc("The writes to make on the far side of this row's relations, in the order they were asked for.\n")
                .addModifiers(KModifier.INTERNAL)
                .returns(LIST.parameterizedBy(Types.nestedWrite))
                .addStatement("return writes.toList()")
                .build(),
        )
    }

    /**
     * The block through which one relation is written from a create.
     *
     * `connectOrCreate` takes the same block as `create`: what it looks the row up by is the first
     * unique key that block covers, which is decided at run time because it depends on what was set.
     */
    fun nestedWriteScope(model: Model, relation: RelationField): TypeSpec {
        val target = relation.targetModel
        val data = types.declared("${target}CreateData")
        val where = types.declared("${target}Where")
        val table = types.declared("${target}Table")
        return TypeSpec.classBuilder(nestedWriteName(model, relation))
            .addKdoc("Rows of `$target` to write together with a `${model.name}`.\n")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("writes", MUTABLE_LIST.parameterizedBy(Types.nestedWrite))
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("writes", MUTABLE_LIST.parameterizedBy(Types.nestedWrite))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("writes")
                    .build(),
            )
            .addFunction(nestedCreate(relation, target, data))
            .addFunction(nestedConnect(model, relation, target, where))
            .addFunction(nestedConnectOrCreate(relation, target, data, table))
            .build()
    }

    private fun nestedCreate(relation: RelationField, target: String, data: TypeName): FunSpec = FunSpec.builder("create")
        .addKdoc("Writes a new `$target` and attaches it.\n")
        .addParameter("block", lambdaOn(data))
        .addStatement("val data = %T().apply(block)", data)
        .addStatement(
            "writes.add(%T.CreateRows(%S, listOf(%T(%S, data.toValues(), %L))))",
            Types.nestedWrite,
            relation.name,
            Types.createSpec,
            target,
            nestedOf(target),
        )
        .build()

    private fun nestedConnect(model: Model, relation: RelationField, target: String, where: TypeName): FunSpec = FunSpec.builder("connect")
        .addKdoc("Attaches an existing `$target`, which the condition must select.\n")
        .addParameter("block", lambdaOn(where))
        .addStatement(
            "val filter = %T().apply(block).build() ?: throw %T(%S)",
            where,
            Types.validationException,
            "`connect` on `${model.name}.${relation.name}` needs a condition saying which `$target` to attach.",
        )
        .addStatement("writes.add(%T.ConnectRows(%S, listOf(filter)))", Types.nestedWrite, relation.name)
        .build()

    private fun nestedConnectOrCreate(relation: RelationField, target: String, data: TypeName, table: TypeName): FunSpec =
        FunSpec.builder("connectOrCreate")
            .addKdoc("Attaches the `$target` these values identify, writing it first if it is not there.\n")
            .addParameter("block", lambdaOn(data))
            .addStatement("val data = %T().apply(block)", data)
            .addStatement("val values = data.toValues()")
            .addStatement("val filter = %T.uniqueFilter(%S, values, %T.UNIQUE_KEYS)", Types.nestedWrites, target, table)
            .addStatement(
                "writes.add(%T.ConnectOrCreateRows(%S, listOf(%T(filter, %T(%S, values, %L)))))",
                Types.nestedWrite,
                relation.name,
                Types.connectOrCreateEntry,
                Types.createSpec,
                target,
                nestedOf(target),
            )
            .build()

    /** A model with no relations has no nested writes to offer, so its payload has no `toNested`. */
    private fun nestedOf(model: String): String =
        if (schema.model(model)?.relationFields.isNullOrEmpty()) "emptyList()" else "data.toNested()"

    private fun nestedWriteName(model: Model, relation: RelationField): String =
        "${model.name}${relation.name.replaceFirstChar { it.uppercase() }}Write"

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

    /**
     * Collects what the block set, keyed by column.
     *
     * Whether everything required is present is decided later, by the runtime: a foreign key that a
     * nested write is about to supply is missing here and present by the time the row is written.
     */
    private fun toValues(model: Model): FunSpec {
        val body = CodeBlock.builder()
            .addStatement("val values = LinkedHashMap<%T, Any?>()", STRING)
        model.fields.filterNot { it.isUpdatedAt }.forEach { field ->
            body.beginControlFlow("if (touched.contains(%S))", field.name)
            body.addStatement("values[%S] = %L", field.dbName, encode(field, access(model, field)))
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
                    .addStatement("val data = %T().apply(block)", dataType)
                    .addStatement(
                        "rows.add(%T.requireFlat(%S, %T(%S, data.toValues(), %L)))",
                        Types.nestedWrites,
                        "createMany",
                        Types.createSpec,
                        model.name,
                        if (model.relationFields.isEmpty()) "emptyList()" else "data.toNested()",
                    )
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
