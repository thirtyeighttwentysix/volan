package io.github.thirtyeighttwentysix.volan.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import io.github.thirtyeighttwentysix.volan.ir.Cardinality
import io.github.thirtyeighttwentysix.volan.ir.FieldType
import io.github.thirtyeighttwentysix.volan.ir.Model
import io.github.thirtyeighttwentysix.volan.ir.ScalarField
import io.github.thirtyeighttwentysix.volan.ir.ScalarType

/**
 * Generates the way a model is summarised: which fields each summary can be asked over, and a result
 * that hands the answers back with their own types.
 *
 * A summary only offers the fields it makes sense for. There is no total of a boolean column and no
 * mean of a JSON one, so neither is offered — the compiler refuses the question rather than the
 * database refusing the query.
 */
internal class AggregateGenerator(private val types: TypeResolver) {
    fun fieldScopes(model: Model): List<TypeSpec> = listOfNotNull(
        fieldScope(model, "Numeric", numeric(model), "The fields of `${model.name}` that can be totalled or averaged."),
        fieldScope(model, "Ordered", ordered(model), "The fields of `${model.name}` that have a smallest and a largest value."),
    )

    private fun fieldScope(model: Model, suffix: String, fields: List<ScalarField>, kdoc: String): TypeSpec? {
        if (fields.isEmpty()) return null
        val builder = TypeSpec.classBuilder("${model.name}${suffix}Fields")
            .addKdoc("%L\n", kdoc)
            .superclass(Types.selectScope)
        fields.forEach { field ->
            builder.addProperty(
                PropertySpec.builder(field.name, UNIT)
                    .addKdoc("Summarises `${field.name}`.\n")
                    .getter(FunSpec.getterBuilder().addStatement("return markSelected(%S)", field.name).build())
                    .build(),
            )
        }
        return builder.build()
    }

    fun scope(model: Model): TypeSpec = TypeSpec.classBuilder("${model.name}AggregateScope")
        .addKdoc("What to work out about `${model.name}`, and over which rows.\n")
        .superclass(Types.aggregateScope)
        .addSuperclassConstructorParameter("%S", model.name)
        .addFunction(
            FunSpec.builder("where")
                .addKdoc("Which rows to summarise.\n")
                .addParameter("block", lambdaOn(types.declared("${model.name}Where")))
                .addStatement("recordFilter(%T().apply(block))", types.declared("${model.name}Where"))
                .build(),
        )
        .addFunctions(summaryFunctions(model))
        .build()

    /**
     * The entry points that ask for a summary.
     *
     * `AggregateScope` and `GroupScope` both record what they are asked for the same way, so the same
     * functions serve a summary of every matching row and a summary of each group.
     */
    fun summaryFunctions(model: Model): List<FunSpec> = buildList {
        add(
            FunSpec.builder("count")
                .addKdoc("Counts the matching rows.\n")
                .addStatement("record(%T.COUNT, null, %S)", Types.aggregateFunction, COUNT_ALIAS)
                .build(),
        )
        if (numeric(model).isNotEmpty()) {
            add(over(model, "sum", "SUM", "Numeric", "Totals the named fields."))
            add(over(model, "average", "AVERAGE", "Numeric", "Averages the named fields."))
        }
        if (ordered(model).isNotEmpty()) {
            add(over(model, "minimum", "MINIMUM", "Ordered", "Takes the smallest value of the named fields."))
            add(over(model, "maximum", "MAXIMUM", "Ordered", "Takes the largest value of the named fields."))
        }
    }

    private fun over(model: Model, name: String, function: String, scope: String, kdoc: String): FunSpec {
        val fields = types.declared("${model.name}${scope}Fields")
        val table = types.declared("${model.name}Table")
        return FunSpec.builder(name)
            .addKdoc("%L\n", kdoc)
            .addParameter("block", lambdaOn(fields))
            .beginControlFlow("%T().apply(block).build().forEach { field ->", fields)
            .addStatement(
                "record(%T.%L, requireNotNull(%T.METADATA.column(field)).column, %S + field)",
                Types.aggregateFunction,
                function,
                table,
                "${aliasPrefix(function)}_",
            )
            .endControlFlow()
            .build()
    }

    fun result(model: Model): TypeSpec = TypeSpec.classBuilder("${model.name}Aggregate")
        .addKdoc(
            "What a query worked out about `${model.name}`.\n\n" +
                "A summary the query did not ask for refuses to be read, rather than reading as zero — " +
                "zero being a perfectly good answer to a question that was asked.\n",
        )
        .primaryConstructor(FunSpec.constructorBuilder().addParameter("values", Types.valueMap).build())
        .addProperty(
            PropertySpec.builder("values", Types.valueMap).addModifiers(KModifier.PRIVATE).initializer("values").build(),
        )
        .addProperties(summaryProperties(model, "there are"))
        .build()

    /**
     * The readers that hand a summary back with its own type.
     *
     * They read out of a property called `values`, which both the whole-table result and one group of
     * a `groupBy` carry, so the same readers describe both.
     *
     * @param subject how to finish "how many rows …", which differs between a summary of everything
     *   and a summary of one group.
     */
    fun summaryProperties(model: Model, subject: String): List<PropertySpec> = buildList {
        add(
            PropertySpec.builder(COUNT_ALIAS, LONG)
                .addKdoc("How many rows $subject.\n")
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement(
                            "return %T.count(values, %S, %S)",
                            Types.aggregateValues,
                            COUNT_ALIAS,
                            "how many `${model.name}` rows $subject",
                        )
                        .build(),
                )
                .build(),
        )
        numeric(model).forEach { field ->
            add(reader(model, field, "sumOf", "sum", Types.bigDecimal.copy(nullable = true), "decimal", "the total of"))
            add(reader(model, field, "averageOf", "avg", DOUBLE_NULLABLE, "double", "the mean of"))
        }
        ordered(model).forEach { field ->
            val scalar = (field.type as FieldType.Scalar).type
            val type = types.fieldType(field.type, Cardinality.REQUIRED).copy(nullable = true)
            val read = Types.aggregateReader(scalar)
            add(reader(model, field, "minimumOf", "min", type, read, "the smallest value of"))
            add(reader(model, field, "maximumOf", "max", type, read, "the largest value of"))
        }
    }

    /**
     * The handles a `having` block compares against.
     *
     * There is one per summary this model can be asked for, named the same as the reader that hands
     * that summary back, so a condition and the value it filters on are written the same way.
     */
    fun havingProperties(model: Model): List<PropertySpec> = buildList {
        add(havingField(COUNT_ALIAS, LONG, "COUNT", null, COUNT_ALIAS, "How many rows are in the group."))
        numeric(model).forEach { field ->
            add(havingSummary(field, "sumOf", "SUM", "sum", Types.bigDecimal, "The total of"))
            add(havingSummary(field, "averageOf", "AVERAGE", "avg", DOUBLE, "The mean of"))
        }
        ordered(model).forEach { field ->
            val type = types.fieldType(field.type, Cardinality.REQUIRED)
            add(havingSummary(field, "minimumOf", "MINIMUM", "min", type, "The smallest value of"))
            add(havingSummary(field, "maximumOf", "MAXIMUM", "max", type, "The largest value of"))
        }
    }

    private fun havingSummary(
        field: ScalarField,
        prefix: String,
        function: String,
        alias: String,
        type: TypeName,
        kdoc: String,
    ): PropertySpec = havingField(
        name = "$prefix${field.name.replaceFirstChar { it.uppercase() }}",
        type = type,
        function = function,
        column = field.dbName,
        alias = "${alias}_${field.name}",
        kdoc = "$kdoc `${field.name}` across the group.",
    )

    private fun havingField(name: String, type: TypeName, function: String, column: String?, alias: String, kdoc: String): PropertySpec =
        PropertySpec.builder(name, Types.orderedFilterField.parameterizedBy(type))
            .addKdoc("%L\n", kdoc)
            .initializer(
                if (column == null) {
                    CodeBlock.of("aggregateField(%T.%L, null, %S)", Types.aggregateFunction, function, alias)
                } else {
                    CodeBlock.of("aggregateField(%T.%L, %S, %S)", Types.aggregateFunction, function, column, alias)
                },
            )
            .build()

    private fun reader(
        model: Model,
        field: ScalarField,
        prefix: String,
        alias: String,
        type: TypeName,
        helper: String,
        description: String,
    ): PropertySpec = PropertySpec.builder("$prefix${field.name.replaceFirstChar { it.uppercase() }}", type)
        .addKdoc("%L `${model.name}.${field.name}`.\n", description.replaceFirstChar { it.uppercase() })
        .getter(
            FunSpec.getterBuilder()
                .addStatement(
                    "return %T.%L(values, %S, %S)",
                    Types.aggregateValues,
                    helper,
                    "${alias}_${field.name}",
                    "$description `${model.name}.${field.name}`",
                )
                .build(),
        )
        .build()

    private fun numeric(model: Model): List<ScalarField> = model.fields.filter { field ->
        field.cardinality != Cardinality.LIST && (field.type as? FieldType.Scalar)?.type in NUMERIC
    }

    private fun ordered(model: Model): List<ScalarField> = model.fields.filter { field ->
        val scalar = (field.type as? FieldType.Scalar)?.type
        field.cardinality != Cardinality.LIST && scalar != null && Types.isOrdered(scalar)
    }

    private fun aliasPrefix(function: String): String = when (function) {
        "SUM" -> "sum"
        "AVERAGE" -> "avg"
        "MINIMUM" -> "min"
        else -> "max"
    }

    private fun lambdaOn(receiver: TypeName): TypeName = LambdaTypeName.get(receiver = receiver, returnType = UNIT)

    private companion object {
        private const val COUNT_ALIAS = "count"
        private val NUMERIC = setOf(ScalarType.INT, ScalarType.LONG, ScalarType.FLOAT, ScalarType.DOUBLE, ScalarType.DECIMAL)
        private val DOUBLE_NULLABLE = ClassName("kotlin", "Double").copy(nullable = true)
    }
}
