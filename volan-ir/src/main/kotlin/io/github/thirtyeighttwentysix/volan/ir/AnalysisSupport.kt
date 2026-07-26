package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.Diagnostic
import io.github.thirtyeighttwentysix.volan.schema.DiagnosticCode
import io.github.thirtyeighttwentysix.volan.schema.Severity
import io.github.thirtyeighttwentysix.volan.schema.SourceSpan
import io.github.thirtyeighttwentysix.volan.schema.ast.Argument
import io.github.thirtyeighttwentysix.volan.schema.ast.ArrayLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.Attribute
import io.github.thirtyeighttwentysix.volan.schema.ast.BlockAttributeDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.BooleanLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.CommentLine
import io.github.thirtyeighttwentysix.volan.schema.ast.ConstantReference
import io.github.thirtyeighttwentysix.volan.schema.ast.FunctionCall
import io.github.thirtyeighttwentysix.volan.schema.ast.Identifier
import io.github.thirtyeighttwentysix.volan.schema.ast.NumberLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.StringLiteral

/** Collects the problems found while analysing a schema. */
internal class DiagnosticSink {
    private val entries = ArrayList<Diagnostic>()

    /** True once anything has been reported that makes the schema unusable. */
    val hasErrors: Boolean
        get() = entries.any { it.isError }

    fun error(code: DiagnosticCode, span: SourceSpan, message: String, label: String? = null, help: String? = null) {
        entries.add(Diagnostic(Severity.ERROR, code, message, span, label, help))
    }

    fun warning(code: DiagnosticCode, span: SourceSpan, message: String, label: String? = null, help: String? = null) {
        entries.add(Diagnostic(Severity.WARNING, code, message, span, label, help))
    }

    /** Everything reported so far, ordered by position in the file. */
    fun toList(): List<Diagnostic> = entries.sortedBy { it.span.start }
}

/**
 * One use of an attribute, whether it was written with `@` on a field or `@@` on a block.
 *
 * Both forms carry the same information, and every check that matters — is the name known, are the
 * arguments right — is the same for both, so analysis works on this rather than on two AST types.
 */
internal class AttributeUse(
    val namespace: String?,
    val simpleName: String,
    val arguments: List<Argument>,
    val span: SourceSpan,
) {
    val qualifiedName: String
        get() = if (namespace == null) simpleName else "$namespace.$simpleName"

    /** The arguments written without a name, in order. */
    val positional: List<Argument>
        get() = arguments.filter { it.name == null }

    /** Returns the argument written as `name: …`, or `null` if there is none. */
    fun named(name: String): Argument? = arguments.firstOrNull { it.name?.text == name }
}

internal fun Attribute.toUse(): AttributeUse = AttributeUse(name.namespace?.text, name.name.text, arguments, span)

internal fun BlockAttributeDeclaration.toUse(): AttributeUse = AttributeUse(name.namespace?.text, name.name.text, arguments, span)

/** Joins `///` comment lines into the documentation of a declaration, or `null` when there is none. */
internal fun documentationOf(comments: List<CommentLine>): String? {
    val lines = comments.filter { it.isDoc }.map { it.text }
    return if (lines.isEmpty()) null else lines.joinToString("\n")
}

/**
 * Reports every argument of [use] that the attribute does not accept.
 *
 * @param allowedNames the named arguments the attribute understands.
 * @param allowedPositional how many unnamed arguments it takes.
 */
internal fun DiagnosticSink.rejectUnknownArguments(use: AttributeUse, allowedNames: List<String>, allowedPositional: Int) {
    use.arguments.forEach { argument ->
        val name = argument.name
        if (name != null && name.text !in allowedNames) {
            error(
                code = SemanticCode.UNKNOWN_ATTRIBUTE_ARGUMENT,
                span = name.span,
                message = "`@${use.qualifiedName}` has no argument `${name.text}`",
                label = if (allowedNames.isEmpty()) "this attribute takes no named arguments" else "unknown argument",
                help = allowedNames.takeIf { it.isNotEmpty() }
                    ?.let { names -> "it accepts ${names.joinToString(", ") { "`$it`" }}" },
            )
        }
    }
    val positional = use.positional
    if (positional.size > allowedPositional) {
        error(
            code = SemanticCode.UNKNOWN_ATTRIBUTE_ARGUMENT,
            span = positional[allowedPositional].span,
            message = "`@${use.qualifiedName}` takes ${countOf(allowedPositional, "unnamed argument")}",
            label = "this one is extra",
        )
    }
}

/** Reads a string argument, reporting when the value is of another kind. */
internal fun DiagnosticSink.readString(argument: Argument, attribute: String): String? {
    val value = argument.value
    if (value is StringLiteral) return value.value
    error(
        code = SemanticCode.INVALID_OPTION_VALUE,
        span = value.span,
        message = "`@$attribute` expects a quoted string here",
        label = "found ${describeExpression(argument)}",
    )
    return null
}

/** Reads a bare name argument, reporting when the value is of another kind. */
internal fun DiagnosticSink.readName(argument: Argument, attribute: String): Identifier? {
    val value = argument.value
    if (value is ConstantReference) return value.name
    error(
        code = SemanticCode.INVALID_OPTION_VALUE,
        span = value.span,
        message = "`@$attribute` expects a name here",
        label = "found ${describeExpression(argument)}",
    )
    return null
}

/** Reads a list of bare names, reporting when the value is not a list or contains something else. */
internal fun DiagnosticSink.readNameList(argument: Argument, attribute: String): List<Identifier>? {
    val value = argument.value
    if (value !is ArrayLiteral) {
        error(
            code = SemanticCode.INVALID_OPTION_VALUE,
            span = value.span,
            message = "`@$attribute` expects a list of field names here",
            label = "found ${describeExpression(argument)}",
            help = "write it as `[fieldA, fieldB]`",
        )
        return null
    }
    val names = ArrayList<Identifier>(value.elements.size)
    value.elements.forEach { element ->
        if (element is ConstantReference) {
            names.add(element.name)
        } else {
            error(
                code = SemanticCode.INVALID_OPTION_VALUE,
                span = element.span,
                message = "`@$attribute` expects field names, written without quotes",
                label = "this is not a field name",
            )
        }
    }
    return names
}

private fun describeExpression(argument: Argument): String = when (val value = argument.value) {
    is StringLiteral -> "a string"
    is NumberLiteral -> "a number"
    is BooleanLiteral -> "a boolean"
    is ArrayLiteral -> "a list"
    is FunctionCall -> "a call to `${value.name.text}()`"
    is ConstantReference -> "the name `${value.name.text}`"
}

/** Renders `1 thing` or `3 things`, so messages read like English rather than like a template. */
internal fun countOf(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"
