package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.Suggestions
import io.github.thirtyeighttwentysix.volan.schema.ast.ArrayLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.BooleanLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.ConstantReference
import io.github.thirtyeighttwentysix.volan.schema.ast.Expression
import io.github.thirtyeighttwentysix.volan.schema.ast.FunctionCall
import io.github.thirtyeighttwentysix.volan.schema.ast.NumberLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.StringLiteral

/**
 * Decides what a `@default(…)` means for the field it is on, and rejects the combinations that cannot
 * work — `@default(now())` on an `Int`, an enum value that the enum does not have, a literal on a list.
 */
internal class DefaultValueAnalyzer(private val sink: DiagnosticSink, private val enums: Map<String, EnumType>) {
    fun resolve(attribute: AttributeUse, field: FieldDescription): DefaultValue? {
        val argument = attribute.positional.firstOrNull()
        if (argument == null) {
            sink.error(
                code = SemanticCode.MISSING_ATTRIBUTE_ARGUMENT,
                span = attribute.span,
                message = "`@default` needs a value",
                label = "no value given",
                help = "write the value in parentheses, for example `@default(now())`",
            )
            return null
        }
        if (field.cardinality.isList) return resolveListDefault(argument.value, field)
        return resolveSingleDefault(argument.value, field)
    }

    private fun resolveListDefault(value: Expression, field: FieldDescription): DefaultValue? {
        if (value is ArrayLiteral && value.elements.isEmpty()) return DefaultValue.EmptyList
        sink.error(
            code = SemanticCode.INVALID_DEFAULT,
            span = value.span,
            message = "a list field can only default to the empty list",
            label = "`${field.name}` holds many values",
            help = "write `@default([])`, or drop the attribute — a list already starts out empty",
        )
        return null
    }

    private fun resolveSingleDefault(value: Expression, field: FieldDescription): DefaultValue? = when (value) {
        is StringLiteral -> literalDefault(DefaultValue.StringValue(value.value), STRING_LIKE, value, field)
        is NumberLiteral -> numberDefault(value, field)
        is BooleanLiteral -> literalDefault(DefaultValue.BooleanValue(value.value), setOf(ScalarType.BOOLEAN), value, field)
        is ConstantReference -> enumDefault(value, field)
        is FunctionCall -> functionDefault(value, field)
        is ArrayLiteral -> {
            reportMismatch(value, field, "a list")
            null
        }
    }

    private fun literalDefault(
        default: DefaultValue,
        accepted: Set<ScalarType>,
        value: Expression,
        field: FieldDescription,
    ): DefaultValue? {
        val scalar = (field.type as? FieldType.Scalar)?.type
        if (scalar != null && scalar in accepted) return default
        reportMismatch(value, field, describe(default))
        return null
    }

    private fun numberDefault(value: NumberLiteral, field: FieldDescription): DefaultValue? {
        val scalar = (field.type as? FieldType.Scalar)?.type
        if (scalar == null || scalar !in NUMERIC) {
            reportMismatch(value, field, "a number")
            return null
        }
        if (!value.isInteger && scalar in INTEGRAL) {
            sink.error(
                code = SemanticCode.INVALID_DEFAULT,
                span = value.span,
                message = "`${value.text}` is not a whole number",
                label = "`${field.name}` is ${field.typeName}",
                help = "remove the decimal part, or change the field to `Decimal` or `Double`",
            )
            return null
        }
        return DefaultValue.NumberValue(value.text)
    }

    private fun enumDefault(value: ConstantReference, field: FieldDescription): DefaultValue? {
        val enumName = (field.type as? FieldType.EnumRef)?.enumName
        if (enumName == null) {
            reportMismatch(value, field, "the name `${value.name.text}`")
            return null
        }
        val enumType = enums[enumName]
        val member = enumType?.value(value.name.text)
        if (member == null) {
            sink.error(
                code = SemanticCode.INVALID_DEFAULT,
                span = value.span,
                message = "`$enumName` has no value `${value.name.text}`",
                label = "unknown enum value",
                help = enumType?.values?.map { it.name }?.let { names ->
                    Suggestions.closest(value.name.text, names)?.let { "did you mean `$it`?" }
                        ?: "its values are ${names.joinToString(", ") { "`$it`" }}"
                },
            )
            return null
        }
        return DefaultValue.EnumValueRef(enumName, member.name)
    }

    private fun functionDefault(call: FunctionCall, field: FieldDescription): DefaultValue? {
        val scalar = (field.type as? FieldType.Scalar)?.type
        return when (call.name.text) {
            "autoincrement" -> generatorDefault(DefaultValue.AutoIncrement, INTEGRAL, scalar, call, field)
            "now" -> generatorDefault(DefaultValue.Now, TEMPORAL, scalar, call, field)
            "uuid" -> generatorDefault(DefaultValue.Uuid, setOf(ScalarType.UUID, ScalarType.STRING), scalar, call, field)
            "cuid" -> generatorDefault(DefaultValue.Cuid, setOf(ScalarType.STRING), scalar, call, field)
            "dbgenerated" -> databaseGenerated(call)
            else -> {
                sink.error(
                    code = SemanticCode.INVALID_DEFAULT,
                    span = call.span,
                    message = "`${call.name.text}()` is not a default Volan can produce",
                    label = "unknown function",
                    help = Suggestions.closest(call.name.text, GENERATORS)?.let { "did you mean `$it()`?" }
                        ?: "the available ones are ${GENERATORS.joinToString(", ") { "`$it()`" }}",
                )
                null
            }
        }
    }

    private fun generatorDefault(
        default: DefaultValue,
        accepted: Set<ScalarType>,
        scalar: ScalarType?,
        call: FunctionCall,
        field: FieldDescription,
    ): DefaultValue? {
        if (call.arguments.isNotEmpty()) {
            sink.error(
                code = SemanticCode.UNKNOWN_ATTRIBUTE_ARGUMENT,
                span = call.span,
                message = "`${call.name.text}()` takes no arguments",
                label = "remove the arguments",
            )
            return null
        }
        if (scalar == null || scalar !in accepted) {
            sink.error(
                code = SemanticCode.INVALID_DEFAULT,
                span = call.span,
                message = "`${call.name.text}()` cannot be the default of ${field.typeName}",
                label = "`${field.name}` is ${field.typeName}",
                help = "it applies to ${accepted.joinToString(", ") { "`${it.schemaName}`" }} fields",
            )
            return null
        }
        return default
    }

    private fun databaseGenerated(call: FunctionCall): DefaultValue? {
        val arguments = call.arguments
        if (arguments.isEmpty()) return DefaultValue.DatabaseGenerated(null)
        val expression = arguments.singleOrNull()?.value as? StringLiteral
        if (expression == null) {
            sink.error(
                code = SemanticCode.INVALID_DEFAULT,
                span = call.span,
                message = "`dbgenerated()` takes at most one quoted expression",
                label = "expected a string or nothing",
                help = "write `@default(dbgenerated(\"gen_random_uuid()\"))`",
            )
            return null
        }
        return DefaultValue.DatabaseGenerated(expression.value)
    }

    private fun reportMismatch(value: Expression, field: FieldDescription, given: String) {
        sink.error(
            code = SemanticCode.INVALID_DEFAULT,
            span = value.span,
            message = "$given cannot be the default of ${field.typeName}",
            label = "`${field.name}` is ${field.typeName}",
        )
    }

    private fun describe(default: DefaultValue): String = when (default) {
        is DefaultValue.StringValue -> "a string"
        is DefaultValue.BooleanValue -> "a boolean"
        else -> "this value"
    }

    private companion object {
        private val INTEGRAL = setOf(ScalarType.INT, ScalarType.LONG)
        private val NUMERIC = setOf(ScalarType.INT, ScalarType.LONG, ScalarType.FLOAT, ScalarType.DOUBLE, ScalarType.DECIMAL)
        private val TEMPORAL = setOf(ScalarType.DATE_TIME, ScalarType.DATE, ScalarType.TIME)
        private val STRING_LIKE = setOf(ScalarType.STRING, ScalarType.JSON, ScalarType.UUID)
        private val GENERATORS = listOf("autoincrement", "now", "uuid", "cuid", "dbgenerated")
    }
}

/** What a default needs to know about the field it is on. */
internal class FieldDescription(
    val name: String,
    val type: FieldType,
    val cardinality: Cardinality,
) {
    /** How the field's type reads in a message, for example ``an `Int` field``. */
    val typeName: String
        get() = when (type) {
            is FieldType.Scalar -> "a `${type.type.schemaName}` field"
            is FieldType.EnumRef -> "a `${type.enumName}` field"
        }
}
