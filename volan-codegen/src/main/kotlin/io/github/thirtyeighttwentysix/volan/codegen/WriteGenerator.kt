package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MUTABLE_LIST
import com.squareup.kotlinpoet.MUTABLE_SET
import com.squareup.kotlinpoet.ParameterSpec
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
        addNestedWrites(builder, model, if (requireMandatory) "Write" else "Change")
        return builder.addFunction(toValues(model)).build()
    }

    /**
     * Adds one block per relation, through which a write can reach the rows on the far side of it.
     *
     * They share one list, so the order the caller wrote them in is the order the runtime sees.
     */
    private fun addNestedWrites(builder: TypeSpec.Builder, model: Model, suffix: String) {
        if (model.relationFields.isEmpty()) return
        builder.addProperty(
            PropertySpec.builder("writes", MUTABLE_LIST.parameterizedBy(Types.nestedWrite))
                .addModifiers(KModifier.PRIVATE)
                .initializer("mutableListOf()")
                .build(),
        )
        model.relationFields.forEach { relation ->
            val scope = types.declared(nestedWriteName(model, relation, suffix))
            builder.addProperty(
                PropertySpec.builder(relation.name, scope)
                    .addKdoc("The `${relation.targetModel}` rows on the other side of `${relation.name}`.\n")
                    .initializer("%T(writes)", scope)
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
    fun nestedWriteScope(model: Model, relation: RelationField): TypeSpec = relationScope(
        model = model,
        relation = relation,
        suffix = "Write",
        kdoc = "The `${relation.targetModel}` rows to write together with a new `${model.name}`.\n",
        extra = emptyList(),
    )

    /**
     * The block through which one relation is reached from an update.
     *
     * A row that already exists can be let go of as well as taken on, so this offers what a create
     * cannot: detaching, replacing the whole set, and changing or deleting what is attached. Which of
     * those appear depends on the relation — a row cannot be detached from a foreign key the schema
     * says is required, so that relation is not offered a `disconnect` at all.
     */
    fun nestedChangeScope(model: Model, relation: RelationField): TypeSpec = relationScope(
        model = model,
        relation = relation,
        suffix = "Change",
        kdoc = "The `${relation.targetModel}` rows on the other side of `${model.name}.${relation.name}`.\n",
        extra = changeFunctions(model, relation),
    )

    private fun relationScope(model: Model, relation: RelationField, suffix: String, kdoc: String, extra: List<FunSpec>): TypeSpec {
        val target = relation.targetModel
        val data = types.declared("${target}CreateData")
        val where = types.declared("${target}Where")
        val table = types.declared("${target}Table")
        return TypeSpec.classBuilder(nestedWriteName(model, relation, suffix))
            .addKdoc(kdoc)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("writes", MUTABLE_LIST.parameterizedBy(Types.nestedWrite))
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("writes", MUTABLE_LIST.parameterizedBy(Types.nestedWrite))
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("writes")
                    .build(),
            )
            .addFunction(nestedCreate(relation, target, data))
            .addFunction(nestedConnect(model, relation, target, where))
            .addFunction(nestedConnectOrCreate(relation, target, data, table))
            .addFunctions(extra)
            .build()
    }

    private fun changeFunctions(model: Model, relation: RelationField): List<FunSpec> {
        val many = relation.cardinality == Cardinality.LIST
        val shape = shapeOf(model, relation)
        return buildList {
            if (shape.detachable) {
                add(if (many) detachMany(relation) else detachOne(relation))
                if (many) add(replaceAll(model, relation))
            }
            add(if (many) alterMany(relation) else alterOne(relation))
            when {
                many -> add(removeMany(relation))
                !shape.owns -> add(removeOne(relation))
            }
        }
    }

    /** The rows a `set` block names, one condition each. */
    fun nestedRowsScope(model: Model, relation: RelationField): TypeSpec? {
        if (relation.cardinality != Cardinality.LIST || !shapeOf(model, relation).detachable) return null
        val where = types.declared("${relation.targetModel}Where")
        val filters = MUTABLE_LIST.parameterizedBy(Types.filter)
        return TypeSpec.classBuilder(nestedWriteName(model, relation, "Rows"))
            .addKdoc("The `${relation.targetModel}` rows that `${model.name}.${relation.name}` should hold.\n")
            .primaryConstructor(FunSpec.constructorBuilder().addModifiers(KModifier.INTERNAL).addParameter("filters", filters).build())
            .addProperty(PropertySpec.builder("filters", filters).addModifiers(KModifier.PRIVATE).initializer("filters").build())
            .addFunction(
                FunSpec.builder("row")
                    .addKdoc("Names one `${relation.targetModel}` the condition must select.\n")
                    .addParameter("block", lambdaOn(where))
                    .addStatement(
                        "filters.add(%T().apply(block).build() ?: throw %T(%S))",
                        where,
                        Types.validationException,
                        "a row of `${model.name}.${relation.name}` needs a condition saying which `${relation.targetModel}` it is.",
                    )
                    .build(),
            )
            .build()
    }

    /**
     * What a relation allows, read off the schema rather than off the row.
     *
     * @property owns whether the row being changed is the one holding the foreign key.
     * @property detachable whether two rows can stop being related without either being deleted, which
     *   a join table always allows and a foreign key allows only when the schema lets it hold null.
     */
    private data class RelationShape(val owns: Boolean, val detachable: Boolean)

    private fun shapeOf(model: Model, field: RelationField): RelationShape {
        val relation = schema.relations.first { it.name == field.relationName }
        if (relation.joinTable != null) return RelationShape(owns = false, detachable = true)
        val owner = schema.model(relation.from.model)
        val nullable = relation.foreignKeyFields.all { owner?.field(it)?.cardinality == Cardinality.OPTIONAL }
        val owns = relation.from.model == model.name && relation.from.field == field.name
        return RelationShape(owns, nullable)
    }

    private fun detachOne(relation: RelationField): FunSpec = FunSpec.builder("disconnect")
        .addKdoc("Lets go of the attached `${relation.targetModel}`, leaving it in the database.\n")
        .addStatement("writes.add(%T.DisconnectRows(%S))", Types.nestedWrite, relation.name)
        .build()

    private fun detachMany(relation: RelationField): FunSpec = FunSpec.builder("disconnect")
        .addKdoc(
            "Lets go of the attached `${relation.targetModel}` rows the condition selects, leaving them " +
                "in the database.\n\nAn empty block lets go of all of them.\n",
        )
        .addParameter(ParameterSpec.builder("block", lambdaOn(types.declared("${relation.targetModel}Where"))).defaultValue("{}").build())
        .addStatement("val filter = %T().apply(block).build()", types.declared("${relation.targetModel}Where"))
        .addStatement("writes.add(%T.DisconnectRows(%S, listOfNotNull(filter)))", Types.nestedWrite, relation.name)
        .build()

    private fun replaceAll(model: Model, relation: RelationField): FunSpec {
        val rows = types.declared(nestedWriteName(model, relation, "Rows"))
        return FunSpec.builder("set")
            .addKdoc(
                "Makes these the attached `${relation.targetModel}` rows, whatever was attached before.\n\n" +
                    "Rows that were attached and are not named here are let go of, not deleted.\n",
            )
            .addParameter("block", lambdaOn(rows))
            .addStatement("val filters = mutableListOf<%T>()", Types.filter)
            .addStatement("%T(filters).apply(block)", rows)
            .addStatement("writes.add(%T.SetRows(%S, filters.toList()))", Types.nestedWrite, relation.name)
            .build()
    }

    private fun alterOne(relation: RelationField): FunSpec {
        val data = types.declared("${relation.targetModel}UpdateData")
        return FunSpec.builder("update")
            .addKdoc("Changes the attached `${relation.targetModel}`.\n")
            .addParameter("block", lambdaOn(data))
            .addStatement("val data = %T().apply(block)", data)
            .addCode(refuseDeeperNesting(relation.targetModel))
            .addStatement("writes.add(%T.UpdateRows(%S, null, data.toValues()))", Types.nestedWrite, relation.name)
            .build()
    }

    private fun alterMany(relation: RelationField): FunSpec {
        val scope = types.declared("${relation.targetModel}UpdateScope")
        return FunSpec.builder("update")
            .addKdoc("Changes the attached `${relation.targetModel}` rows the `where` block selects, or all of them.\n")
            .addParameter("block", lambdaOn(scope))
            .addStatement("val scope = %T().apply(block)", scope)
            .addStatement("val data = scope.payload")
            .addCode(refuseDeeperNesting(relation.targetModel))
            .addStatement(
                "writes.add(%T.UpdateRows(%S, scope.filter.build(), data.toValues()))",
                Types.nestedWrite,
                relation.name,
            )
            .build()
    }

    private fun removeOne(relation: RelationField): FunSpec = FunSpec.builder("delete")
        .addKdoc("Deletes the attached `${relation.targetModel}`.\n")
        .addStatement("writes.add(%T.DeleteRows(%S, null))", Types.nestedWrite, relation.name)
        .build()

    private fun removeMany(relation: RelationField): FunSpec = FunSpec.builder("delete")
        .addKdoc("Deletes the attached `${relation.targetModel}` rows the condition selects.\n\nAn empty block deletes all of them.\n")
        .addParameter(ParameterSpec.builder("block", lambdaOn(types.declared("${relation.targetModel}Where"))).defaultValue("{}").build())
        .addStatement("val filter = %T().apply(block).build()", types.declared("${relation.targetModel}Where"))
        .addStatement("writes.add(%T.DeleteRows(%S, filter))", Types.nestedWrite, relation.name)
        .build()

    /**
     * A nested update writes columns, not shapes.
     *
     * Reaching a third level down would need the key of a row nobody has read yet, so it is refused
     * where it was written rather than silently dropped.
     */

    /** `createMany` puts every row in one statement, which leaves nowhere to write a relation between them. */
    private fun refuseNestedRows(model: Model): CodeBlock {
        if (model.relationFields.isEmpty()) return CodeBlock.of("")
        return CodeBlock.of(
            "%T.requireFlat(%S, data.toNested(), %S, %S)\n",
            Types.nestedWrites,
            "createMany",
            "writes its rows in one statement",
            "Use `create` once per row when the rows bring relations with them.",
        )
    }

    private fun refuseDeeperNesting(target: String): CodeBlock {
        if (schema.model(target)?.relationFields.isNullOrEmpty()) return CodeBlock.of("")
        return CodeBlock.of(
            "%T.requireFlat(%S, data.toNested(), %S, %S)\n",
            Types.nestedWrites,
            "a nested update",
            "changes the columns of rows that are already there",
            "Change them from their own repository when they bring relations with them.",
        )
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

    private fun nestedWriteName(model: Model, relation: RelationField, suffix: String): String =
        "${model.name}${relation.name.replaceFirstChar { it.uppercase() }}$suffix"

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
                    .addCode(refuseNestedRows(model))
                    .addStatement("rows.add(%T(%S, data.toValues()))", Types.createSpec, model.name)
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
