package io.github.thirtyeighttwentysix.volan.schema

import io.github.thirtyeighttwentysix.volan.schema.ast.Argument
import io.github.thirtyeighttwentysix.volan.schema.ast.ArrayLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.Attribute
import io.github.thirtyeighttwentysix.volan.schema.ast.BlockAttributeDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.BooleanLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.CommentLine
import io.github.thirtyeighttwentysix.volan.schema.ast.ConfigEntry
import io.github.thirtyeighttwentysix.volan.schema.ast.ConstantReference
import io.github.thirtyeighttwentysix.volan.schema.ast.DatasourceDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.Declaration
import io.github.thirtyeighttwentysix.volan.schema.ast.Documented
import io.github.thirtyeighttwentysix.volan.schema.ast.EnumDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.EnumValueDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.Expression
import io.github.thirtyeighttwentysix.volan.schema.ast.FieldDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.FunctionCall
import io.github.thirtyeighttwentysix.volan.schema.ast.GeneratorDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.ModelDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.NumberLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.SchemaDocument
import io.github.thirtyeighttwentysix.volan.schema.ast.StringLiteral

/**
 * Prints a parsed schema back as canonical `schema.volan` text.
 *
 * The canonical form is the one the reference documentation uses: two-space indentation, field names
 * and types aligned in columns, configuration properties aligned on `=`, one blank line between
 * declarations. Comments and the blank lines the author put between fields are preserved; everything
 * else about the layout is normalised.
 *
 * Formatting is idempotent: formatting already-formatted text returns it unchanged.
 */
public object SchemaFormatter {
    private const val INDENT = "  "

    /**
     * Formats a parsed [document].
     */
    @JvmStatic
    public fun format(document: SchemaDocument): String {
        val out = StringBuilder()
        document.declarations.forEachIndexed { position, declaration ->
            if (position > 0) out.append('\n')
            appendDeclaration(out, declaration)
        }
        if (document.trailingComments.isNotEmpty()) {
            if (document.declarations.isNotEmpty()) out.append('\n')
            document.trailingComments.forEach { out.append(renderComment(it)).append('\n') }
        }
        return out.toString()
    }

    /**
     * Parses [source] and formats it.
     *
     * @throws VolanSchemaException if the schema contains errors; unparseable text cannot be formatted.
     */
    @JvmStatic
    public fun format(source: SourceFile): String = format(SchemaParser.parse(source).documentOrThrow())

    /**
     * Parses schema [text] reported under the given file [name] and formats it.
     *
     * @throws VolanSchemaException if the schema contains errors.
     */
    @JvmStatic
    public fun format(name: String, text: String): String = format(SourceFile(name, text))

    private fun appendDeclaration(out: StringBuilder, declaration: Declaration) {
        declaration.leadingComments.forEach { out.append(renderComment(it)).append('\n') }
        out.append(keywordOf(declaration)).append(' ').append(declaration.name.text).append(" {\n")
        when (declaration) {
            is DatasourceDeclaration -> appendConfigEntries(out, declaration.entries)
            is GeneratorDeclaration -> appendConfigEntries(out, declaration.entries)
            is ModelDeclaration -> appendModelMembers(out, declaration)
            is EnumDeclaration -> appendEnumMembers(out, declaration)
        }
        declaration.trailingComments.forEach { out.append(INDENT).append(renderComment(it)).append('\n') }
        out.append('}')
        declaration.trailingComment?.let { out.append(' ').append(renderComment(it)) }
        out.append('\n')
    }

    private fun keywordOf(declaration: Declaration): String = when (declaration) {
        is DatasourceDeclaration -> "datasource"
        is GeneratorDeclaration -> "generator"
        is ModelDeclaration -> "model"
        is EnumDeclaration -> "enum"
    }

    private fun appendConfigEntries(out: StringBuilder, entries: List<ConfigEntry>) {
        val keyWidth = entries.maxOfOrNull { it.key.text.length } ?: 0
        entries.forEachIndexed { position, entry ->
            appendLeading(out, entry, position)
            val line = INDENT + entry.key.text.padEnd(keyWidth) + " = " + renderExpression(entry.value)
            appendLine(out, line, entry.trailingComment)
        }
    }

    private fun appendModelMembers(out: StringBuilder, model: ModelDeclaration) {
        val fields = model.fields
        val nameWidth = fields.maxOfOrNull { it.name.text.length } ?: 0
        val typeWidth = fields.maxOfOrNull { it.type.toString().length } ?: 0
        model.members.forEachIndexed { position, member ->
            appendLeading(out, member, position)
            when (member) {
                is FieldDeclaration -> appendLine(out, renderField(member, nameWidth, typeWidth), member.trailingComment)
                is BlockAttributeDeclaration -> appendLine(out, INDENT + renderBlockAttribute(member), member.trailingComment)
            }
        }
    }

    private fun appendEnumMembers(out: StringBuilder, enumDeclaration: EnumDeclaration) {
        enumDeclaration.members.forEachIndexed { position, member ->
            appendLeading(out, member, position)
            when (member) {
                is EnumValueDeclaration -> appendLine(out, renderEnumValue(member), member.trailingComment)
                is BlockAttributeDeclaration -> appendLine(out, INDENT + renderBlockAttribute(member), member.trailingComment)
            }
        }
    }

    private fun appendLeading(out: StringBuilder, member: Documented, position: Int) {
        if (member.blankLineBefore && position > 0) out.append('\n')
        member.leadingComments.forEach { out.append(INDENT).append(renderComment(it)).append('\n') }
    }

    private fun appendLine(out: StringBuilder, line: String, trailingComment: CommentLine?) {
        out.append(line.trimEnd())
        trailingComment?.let { out.append(' ').append(renderComment(it)) }
        out.append('\n')
    }

    private fun renderField(field: FieldDeclaration, nameWidth: Int, typeWidth: Int): String {
        val builder = StringBuilder(INDENT)
        builder.append(field.name.text.padEnd(nameWidth)).append(' ')
        builder.append(field.type.toString().padEnd(typeWidth))
        field.attributes.forEach { builder.append(' ').append(renderAttribute(it)) }
        return builder.toString()
    }

    private fun renderEnumValue(value: EnumValueDeclaration): String {
        val builder = StringBuilder(INDENT).append(value.name.text)
        value.attributes.forEach { builder.append(' ').append(renderAttribute(it)) }
        return builder.toString()
    }

    private fun renderAttribute(attribute: Attribute): String = "@" + attribute.name.qualifiedName + renderArguments(attribute.arguments)

    private fun renderBlockAttribute(attribute: BlockAttributeDeclaration): String =
        "@@" + attribute.name.qualifiedName + renderArguments(attribute.arguments)

    private fun renderArguments(arguments: List<Argument>): String =
        if (arguments.isEmpty()) "" else arguments.joinToString(", ", prefix = "(", postfix = ")") { renderArgument(it) }

    private fun renderArgument(argument: Argument): String {
        val value = renderExpression(argument.value)
        val name = argument.name
        return if (name == null) value else "${name.text}: $value"
    }

    private fun renderExpression(expression: Expression): String = when (expression) {
        is StringLiteral -> expression.raw
        is NumberLiteral -> expression.text
        is BooleanLiteral -> expression.value.toString()
        is ConstantReference -> expression.name.text
        is FunctionCall -> expression.name.text + "(" + expression.arguments.joinToString(", ") { renderArgument(it) } + ")"
        is ArrayLiteral -> expression.elements.joinToString(", ", prefix = "[", postfix = "]") { renderExpression(it) }
    }

    private fun renderComment(comment: CommentLine): String {
        val marker = if (comment.isDoc) "///" else "//"
        return if (comment.text.isEmpty()) marker else "$marker ${comment.text}"
    }
}
