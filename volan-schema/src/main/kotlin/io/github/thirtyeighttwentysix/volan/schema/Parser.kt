package io.github.thirtyeighttwentysix.volan.schema

import io.github.thirtyeighttwentysix.volan.schema.ast.Argument
import io.github.thirtyeighttwentysix.volan.schema.ast.ArrayLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.Attribute
import io.github.thirtyeighttwentysix.volan.schema.ast.AttributeName
import io.github.thirtyeighttwentysix.volan.schema.ast.BlockAttributeDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.BooleanLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.CommentLine
import io.github.thirtyeighttwentysix.volan.schema.ast.ConfigEntry
import io.github.thirtyeighttwentysix.volan.schema.ast.ConstantReference
import io.github.thirtyeighttwentysix.volan.schema.ast.DatasourceDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.Declaration
import io.github.thirtyeighttwentysix.volan.schema.ast.EnumDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.EnumMember
import io.github.thirtyeighttwentysix.volan.schema.ast.EnumValueDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.Expression
import io.github.thirtyeighttwentysix.volan.schema.ast.FieldDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.FunctionCall
import io.github.thirtyeighttwentysix.volan.schema.ast.GeneratorDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.Identifier
import io.github.thirtyeighttwentysix.volan.schema.ast.ModelDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.ModelMember
import io.github.thirtyeighttwentysix.volan.schema.ast.NumberLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.SchemaDocument
import io.github.thirtyeighttwentysix.volan.schema.ast.StringLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.TypeArity
import io.github.thirtyeighttwentysix.volan.schema.ast.TypeReference

/** The document produced by a parse, together with every problem found on the way. */
internal class ParseOutcome(val document: SchemaDocument, val diagnostics: List<Diagnostic>)

/**
 * A recursive-descent parser for the schema language.
 *
 * The parser recovers from every error it reports: after a bad field it resumes at the next line, and
 * after a bad declaration it resumes at the next top-level keyword. A file with five mistakes
 * therefore produces five diagnostics rather than one, which is the whole point of parsing by hand.
 */
// A recursive-descent parser is one cohesive state machine over a token stream: every production needs
// the same cursor, the same lookahead and the same diagnostic sink. Splitting it across classes would
// buy a smaller file at the cost of threading that state through constructors.
@Suppress("TooManyFunctions", "LargeClass")
internal class Parser(private val source: SourceFile, private val tokens: List<Token>) {
    private var index: Int = 0
    private val diagnostics = ArrayList<Diagnostic>()

    fun parse(): ParseOutcome {
        val declarations = ArrayList<Declaration>()
        var trailingComments: List<CommentLine> = emptyList()
        while (true) {
            val trivia = readTrivia()
            if (check(TokenType.END_OF_FILE)) {
                trailingComments = trivia.comments
                break
            }
            val before = index
            val declaration = parseDeclaration(trivia)
            if (declaration != null) declarations.add(declaration)
            if (index == before) advance()
        }
        val document = SchemaDocument(declarations, trailingComments, SourceSpan(0, source.text.length))
        return ParseOutcome(document, diagnostics)
    }

    private fun parseDeclaration(trivia: Trivia): Declaration? {
        val token = peek()
        if (token.type != TokenType.IDENTIFIER) {
            reportUnexpectedTopLevelToken(token)
            advance()
            synchronizeTopLevel()
            return null
        }
        return when (token.value) {
            "datasource" -> parseConfigBlock(trivia, isDatasource = true)
            "generator" -> parseConfigBlock(trivia, isDatasource = false)
            "model" -> parseModel(trivia)
            "enum" -> parseEnum(trivia)
            else -> {
                reportUnknownDeclaration(token)
                advance()
                synchronizeTopLevel()
                null
            }
        }
    }

    private fun parseModel(trivia: Trivia): Declaration? {
        val keyword = advance()
        val name = expectDeclarationName("model") ?: run {
            synchronizeTopLevel()
            return null
        }
        if (!expectLeftBrace("model", name)) {
            synchronizeTopLevel()
            return null
        }
        val errorsBefore = diagnostics.size
        val body = parseBlockBody("model", name) { memberTrivia -> parseModelMember(memberTrivia) }
        // Only complain about emptiness when nothing else went wrong: a model whose only field failed
        // to parse is already reporting the real problem, and "declares no fields" would just be noise.
        if (body.closed && diagnostics.size == errorsBefore && body.members.none { it is FieldDeclaration }) {
            report(
                code = SyntaxCode.EMPTY_MODEL,
                span = SourceSpan(name.span.start, body.endOffset),
                message = "model `${name.text}` declares no fields",
                label = "a model needs at least one field",
                help = "add a field, for example `id Int @id @default(autoincrement())`",
            )
        }
        return ModelDeclaration(
            name = name,
            members = body.members,
            leadingComments = trivia.comments,
            trailingComment = readTrailingComment(),
            blankLineBefore = trivia.blankLineBefore,
            trailingComments = body.trailingComments,
            span = SourceSpan(keyword.span.start, body.endOffset),
        )
    }

    private fun parseEnum(trivia: Trivia): Declaration? {
        val keyword = advance()
        val name = expectDeclarationName("enum") ?: run {
            synchronizeTopLevel()
            return null
        }
        if (!expectLeftBrace("enum", name)) {
            synchronizeTopLevel()
            return null
        }
        val errorsBefore = diagnostics.size
        val body = parseBlockBody("enum", name) { memberTrivia -> parseEnumMember(memberTrivia) }
        if (body.closed && diagnostics.size == errorsBefore && body.members.none { it is EnumValueDeclaration }) {
            report(
                code = SyntaxCode.EMPTY_ENUM,
                span = SourceSpan(name.span.start, body.endOffset),
                message = "enum `${name.text}` declares no values",
                label = "an enum needs at least one value",
                help = "add a value, for example `USER`",
            )
        }
        return EnumDeclaration(
            name = name,
            members = body.members,
            leadingComments = trivia.comments,
            trailingComment = readTrailingComment(),
            blankLineBefore = trivia.blankLineBefore,
            trailingComments = body.trailingComments,
            span = SourceSpan(keyword.span.start, body.endOffset),
        )
    }

    private fun parseConfigBlock(trivia: Trivia, isDatasource: Boolean): Declaration? {
        val kind = if (isDatasource) "datasource" else "generator"
        val keyword = advance()
        val name = expectDeclarationName(kind) ?: run {
            synchronizeTopLevel()
            return null
        }
        if (!expectLeftBrace(kind, name)) {
            synchronizeTopLevel()
            return null
        }
        val body = parseBlockBody(kind, name) { entryTrivia -> parseConfigEntry(entryTrivia, kind) }
        val entries = body.members
        val trailingComment = readTrailingComment()
        val span = SourceSpan(keyword.span.start, body.endOffset)
        return if (isDatasource) {
            DatasourceDeclaration(name, entries, trivia.comments, trailingComment, trivia.blankLineBefore, body.trailingComments, span)
        } else {
            GeneratorDeclaration(name, entries, trivia.comments, trailingComment, trivia.blankLineBefore, body.trailingComments, span)
        }
    }

    private class BlockBody<T>(
        val members: List<T>,
        val trailingComments: List<CommentLine>,
        val endOffset: Int,
        val closed: Boolean,
    )

    private fun <T : Any> parseBlockBody(kind: String, name: Identifier, parseMember: (Trivia) -> T?): BlockBody<T> {
        val members = ArrayList<T>()
        while (true) {
            val memberTrivia = readTrivia()
            if (check(TokenType.RIGHT_BRACE)) {
                val close = advance()
                return BlockBody(members, memberTrivia.comments, close.span.end, closed = true)
            }
            if (check(TokenType.END_OF_FILE)) {
                reportUnclosedBlock(kind, name)
                return BlockBody(members, memberTrivia.comments, peek().span.end, closed = false)
            }
            val before = index
            val member = parseMember(memberTrivia)
            if (member != null) {
                members.add(member)
            } else {
                if (index == before) advance()
                skipToNextLineInBlock()
            }
        }
    }

    private fun parseModelMember(trivia: Trivia): ModelMember? = when (peek().type) {
        TokenType.AT_AT -> parseBlockAttribute(trivia, wrongForm = false)
        TokenType.AT -> parseBlockAttribute(trivia, wrongForm = true)
        TokenType.IDENTIFIER -> parseField(trivia)
        else -> {
            reportUnexpectedTokenInBlock(peek(), "a field name or a `@@` attribute")
            null
        }
    }

    private fun parseEnumMember(trivia: Trivia): EnumMember? = when (peek().type) {
        TokenType.AT_AT -> parseBlockAttribute(trivia, wrongForm = false)
        TokenType.AT -> parseBlockAttribute(trivia, wrongForm = true)
        TokenType.IDENTIFIER -> parseEnumValue(trivia)
        else -> {
            reportUnexpectedTokenInBlock(peek(), "an enum value or a `@@` attribute")
            null
        }
    }

    private fun parseField(trivia: Trivia): FieldDeclaration? {
        val nameToken = advance()
        val name = Identifier(nameToken.value, nameToken.span)
        val type = parseTypeReference() ?: return null
        val attributes = parseFieldAttributes()
        val end = attributes.lastOrNull()?.span?.end ?: type.span.end
        return FieldDeclaration(
            name = name,
            type = type,
            attributes = attributes,
            leadingComments = trivia.comments,
            trailingComment = readTrailingComment(),
            blankLineBefore = trivia.blankLineBefore,
            span = SourceSpan(nameToken.span.start, end),
        )
    }

    private fun parseEnumValue(trivia: Trivia): EnumValueDeclaration {
        val nameToken = advance()
        val attributes = parseFieldAttributes()
        val end = attributes.lastOrNull()?.span?.end ?: nameToken.span.end
        return EnumValueDeclaration(
            name = Identifier(nameToken.value, nameToken.span),
            attributes = attributes,
            leadingComments = trivia.comments,
            trailingComment = readTrailingComment(),
            blankLineBefore = trivia.blankLineBefore,
            span = SourceSpan(nameToken.span.start, end),
        )
    }

    private fun parseConfigEntry(trivia: Trivia, kind: String): ConfigEntry? {
        if (check(TokenType.AT) || check(TokenType.AT_AT)) {
            report(
                code = SyntaxCode.ATTRIBUTE_NOT_ALLOWED_HERE,
                span = peek().span,
                message = "attributes are not allowed in a `$kind` block",
                label = "only `name = value` properties belong here",
            )
            return null
        }
        val keyToken = advance()
        if (!check(TokenType.EQUALS)) {
            report(
                code = SyntaxCode.EXPECTED_EQUALS,
                span = peek().span,
                message = "expected `=` after `${keyToken.value}`",
                label = "found ${describe(peek())}",
                help = "configuration properties are written as `name = value`",
            )
            return null
        }
        advance()
        if (!canStartExpression(peek())) {
            report(
                code = SyntaxCode.EXPECTED_CONFIGURATION_VALUE,
                span = peek().span,
                message = "expected a value for `${keyToken.value}`",
                label = "found ${describe(peek())}",
                help = "values are strings, numbers, booleans or calls such as `env(\"DATABASE_URL\")`",
            )
            return null
        }
        val value = parseExpression() ?: return null
        return ConfigEntry(
            key = Identifier(keyToken.value, keyToken.span),
            value = value,
            leadingComments = trivia.comments,
            trailingComment = readTrailingComment(),
            blankLineBefore = trivia.blankLineBefore,
            span = SourceSpan(keyToken.span.start, value.span.end),
        )
    }

    private fun parseTypeReference(): TypeReference? {
        val token = peek()
        if (token.type != TokenType.IDENTIFIER) {
            report(
                code = SyntaxCode.EXPECTED_FIELD_TYPE,
                span = token.span,
                message = "expected a field type",
                label = "found ${describe(token)}",
                help = "a field is written as `name Type`, for example `email String`",
            )
            return null
        }
        advance()
        val name = Identifier(token.value, token.span)
        return when {
            check(TokenType.LEFT_BRACKET) -> parseListType(name)
            check(TokenType.QUESTION) -> parseOptionalType(name)
            else -> TypeReference(name, TypeArity.REQUIRED, token.span)
        }
    }

    private fun parseListType(name: Identifier): TypeReference? {
        val open = advance()
        if (!check(TokenType.RIGHT_BRACKET)) {
            report(
                code = SyntaxCode.UNCLOSED_LIST,
                span = open.span,
                message = "expected `]` to close the list type",
                label = "found ${describe(peek())}",
                help = "a list type is written as `Post[]`",
            )
            return null
        }
        var end = advance().span.end
        while (check(TokenType.LEFT_BRACKET)) {
            val extra = advance()
            if (check(TokenType.RIGHT_BRACKET)) end = advance().span.end
            report(
                code = SyntaxCode.NESTED_LIST_TYPE,
                span = SourceSpan(extra.span.start, end),
                message = "nested list types are not supported",
                label = "remove this `[]`",
                help = "a field is either a single value or a list of values, never a list of lists",
            )
        }
        if (check(TokenType.QUESTION)) {
            val question = advance()
            end = question.span.end
            report(
                code = SyntaxCode.OPTIONAL_LIST_TYPE,
                span = question.span,
                message = "a list type cannot be optional",
                label = "remove this `?`",
                help = "an empty list already means `no values`, so `${name.text}[]` covers this case",
            )
        }
        return TypeReference(name, TypeArity.LIST, SourceSpan(name.span.start, end))
    }

    private fun parseOptionalType(name: Identifier): TypeReference {
        var end = advance().span.end
        while (check(TokenType.QUESTION)) {
            val extra = advance()
            end = extra.span.end
            report(
                code = SyntaxCode.REPEATED_OPTIONAL_MARKER,
                span = extra.span,
                message = "a type carries at most one `?`",
                label = "remove this `?`",
            )
        }
        return TypeReference(name, TypeArity.OPTIONAL, SourceSpan(name.span.start, end))
    }

    /**
     * Reads the attributes that belong to the field or enum value just parsed.
     *
     * Attributes bind to the line they are written on. Without that rule a `@@index` on the line below
     * a field would be swallowed as one of that field's attributes, since the grammar is otherwise
     * insensitive to line breaks.
     */
    private fun parseFieldAttributes(): List<Attribute> {
        val attributes = ArrayList<Attribute>()
        while ((check(TokenType.AT) || check(TokenType.AT_AT)) && !peek().newlineBefore) {
            if (check(TokenType.AT_AT)) {
                report(
                    code = SyntaxCode.WRONG_ATTRIBUTE_FORM,
                    span = peek().span,
                    message = "field attributes are written with a single `@`",
                    label = "found `@@`, which applies to the whole model",
                    help = "write `@id` on a field and `@@id([a, b])` on its own line inside the model",
                )
            }
            val before = index
            val attribute = parseAttribute() ?: break
            attributes.add(attribute)
            if (index == before) break
        }
        return attributes
    }

    private fun parseBlockAttribute(trivia: Trivia, wrongForm: Boolean): BlockAttributeDeclaration? {
        if (wrongForm) {
            report(
                code = SyntaxCode.WRONG_ATTRIBUTE_FORM,
                span = peek().span,
                message = "attributes that apply to the whole block are written with `@@`",
                label = "found `@`, which applies to a single field",
                help = "write `@@index([email])` here, or move `@index` onto the field it belongs to",
            )
        }
        val start = peek().span.start
        val attribute = parseAttribute() ?: return null
        return BlockAttributeDeclaration(
            name = attribute.name,
            arguments = attribute.arguments,
            leadingComments = trivia.comments,
            trailingComment = readTrailingComment(),
            blankLineBefore = trivia.blankLineBefore,
            span = SourceSpan(start, attribute.span.end),
        )
    }

    private fun parseAttribute(): Attribute? {
        val marker = advance()
        val name = parseAttributeName() ?: return null
        val arguments = if (check(TokenType.LEFT_PAREN)) parseArgumentList() else emptyList()
        val end = if (arguments.isEmpty() && previous().type != TokenType.RIGHT_PAREN) name.span.end else previous().span.end
        return Attribute(name, arguments, SourceSpan(marker.span.start, end))
    }

    private fun parseAttributeName(): AttributeName? {
        if (!check(TokenType.IDENTIFIER)) {
            report(
                code = SyntaxCode.EXPECTED_ATTRIBUTE_NAME,
                span = peek().span,
                message = "expected an attribute name",
                label = "found ${describe(peek())}",
                help = "attributes are written as `@name` or `@namespace.Name`, for example `@db.VarChar(200)`",
            )
            return null
        }
        val first = advance()
        if (!check(TokenType.DOT)) {
            return AttributeName(null, Identifier(first.value, first.span), first.span)
        }
        advance()
        if (!check(TokenType.IDENTIFIER)) {
            report(
                code = SyntaxCode.EXPECTED_ATTRIBUTE_MEMBER,
                span = peek().span,
                message = "expected a name after `${first.value}.`",
                label = "found ${describe(peek())}",
                help = "native type attributes are written as `@db.VarChar(200)`",
            )
            return null
        }
        val second = advance()
        return AttributeName(
            namespace = Identifier(first.value, first.span),
            name = Identifier(second.value, second.span),
            span = first.span.union(second.span),
        )
    }

    private fun parseArgumentList(): List<Argument> {
        val open = advance()
        val arguments = ArrayList<Argument>()
        while (!check(TokenType.RIGHT_PAREN)) {
            if (check(TokenType.END_OF_FILE) || check(TokenType.RIGHT_BRACE)) {
                report(
                    code = SyntaxCode.UNCLOSED_ARGUMENT_LIST,
                    span = open.span,
                    message = "expected `)` to close the argument list",
                    label = "this `(` is never closed",
                )
                return arguments
            }
            val before = index
            parseArgument()?.let { arguments.add(it) }
            if (index == before) advance()
            if (check(TokenType.COMMA)) advance()
        }
        advance()
        return arguments
    }

    private fun parseArgument(): Argument? {
        if (check(TokenType.IDENTIFIER) && peek(1).type == TokenType.COLON) return parseNamedArgument()
        val value = parseExpression() ?: return null
        return Argument(null, value, value.span)
    }

    private fun parseNamedArgument(): Argument? {
        val nameToken = advance()
        advance()
        if (!canStartExpression(peek())) {
            report(
                code = SyntaxCode.EXPECTED_ARGUMENT_VALUE,
                span = peek().span,
                message = "named argument `${nameToken.value}` has no value",
                label = "found ${describe(peek())}",
                help = "write the value after the colon, for example `${nameToken.value}: [id]`",
            )
            return null
        }
        val value = parseExpression() ?: return null
        return Argument(Identifier(nameToken.value, nameToken.span), value, SourceSpan(nameToken.span.start, value.span.end))
    }

    private fun parseExpression(): Expression? {
        val token = peek()
        return when (token.type) {
            TokenType.STRING -> {
                advance()
                StringLiteral(token.value, token.text, token.span)
            }
            TokenType.NUMBER -> {
                advance()
                NumberLiteral(token.text, token.span)
            }
            TokenType.LEFT_BRACKET -> parseArrayLiteral()
            TokenType.IDENTIFIER -> parseIdentifierExpression()
            else -> {
                report(
                    code = SyntaxCode.EXPECTED_EXPRESSION,
                    span = token.span,
                    message = "expected a value",
                    label = "found ${describe(token)}",
                    help = "values are strings, numbers, booleans, names, lists or calls such as `now()`",
                )
                null
            }
        }
    }

    private fun parseIdentifierExpression(): Expression {
        val token = advance()
        val name = Identifier(token.value, token.span)
        if (check(TokenType.LEFT_PAREN)) {
            val arguments = parseArgumentList()
            return FunctionCall(name, arguments, SourceSpan(token.span.start, previous().span.end))
        }
        return when (token.value) {
            "true" -> BooleanLiteral(true, token.span)
            "false" -> BooleanLiteral(false, token.span)
            else -> ConstantReference(name, token.span)
        }
    }

    private fun parseArrayLiteral(): Expression {
        val open = advance()
        val elements = ArrayList<Expression>()
        while (!check(TokenType.RIGHT_BRACKET)) {
            if (isListTerminator()) {
                report(
                    code = SyntaxCode.UNCLOSED_LIST,
                    span = open.span,
                    message = "expected `]` to close the list",
                    label = "this `[` is never closed",
                )
                return ArrayLiteral(elements, SourceSpan(open.span.start, previous().span.end))
            }
            val before = index
            parseExpression()?.let { elements.add(it) }
            if (index == before) advance()
            if (check(TokenType.COMMA)) advance()
        }
        val close = advance()
        return ArrayLiteral(elements, SourceSpan(open.span.start, close.span.end))
    }

    private fun isListTerminator(): Boolean = check(TokenType.END_OF_FILE) || check(TokenType.RIGHT_BRACE) || check(TokenType.RIGHT_PAREN)

    private class Trivia(val comments: List<CommentLine>, val blankLineBefore: Boolean)

    private fun readTrivia(): Trivia {
        val comments = ArrayList<CommentLine>()
        var blankLineBefore: Boolean? = null
        while (peek().isComment) {
            val token = advance()
            if (blankLineBefore == null) blankLineBefore = token.blankLineBefore
            comments.add(CommentLine(token.value, token.type == TokenType.DOC_COMMENT, token.span))
        }
        return Trivia(comments, blankLineBefore ?: peek().blankLineBefore)
    }

    private fun readTrailingComment(): CommentLine? {
        val token = peek()
        if (!token.isComment || token.newlineBefore) return null
        advance()
        return CommentLine(token.value, token.type == TokenType.DOC_COMMENT, token.span)
    }

    private fun synchronizeTopLevel() {
        while (!check(TokenType.END_OF_FILE)) {
            val token = peek()
            if (token.newlineBefore && token.type == TokenType.IDENTIFIER && token.value in DECLARATION_KEYWORDS) return
            advance()
        }
    }

    private fun skipToNextLineInBlock() {
        while (!check(TokenType.END_OF_FILE) && !check(TokenType.RIGHT_BRACE) && !peek().newlineBefore) {
            advance()
        }
    }

    private fun expectDeclarationName(kind: String): Identifier? {
        val token = peek()
        if (token.type == TokenType.IDENTIFIER) {
            advance()
            return Identifier(token.value, token.span)
        }
        report(
            code = SyntaxCode.EXPECTED_NAME,
            span = token.span,
            message = "expected a name for the $kind",
            label = "found ${describe(token)}",
            help = if (token.type == TokenType.STRING) {
                "names are written without quotes, for example `$kind ${token.value}`"
            } else {
                "a $kind is written as `$kind Name { … }`"
            },
        )
        return null
    }

    private fun expectLeftBrace(kind: String, name: Identifier): Boolean {
        if (check(TokenType.LEFT_BRACE)) {
            advance()
            return true
        }
        report(
            code = SyntaxCode.EXPECTED_OPENING_BRACE,
            span = peek().span,
            message = "expected `{` after `$kind ${name.text}`",
            label = "found ${describe(peek())}",
            help = "the body of a $kind is written in braces: `$kind ${name.text} { … }`",
        )
        return false
    }

    private fun reportUnclosedBlock(kind: String, name: Identifier) {
        report(
            code = SyntaxCode.UNCLOSED_BLOCK,
            span = name.span,
            message = "unclosed $kind `${name.text}`",
            label = "this block is never closed",
            help = "add a `}` to close `$kind ${name.text}`",
        )
    }

    private fun reportUnknownDeclaration(token: Token) {
        val suggestion = Suggestions.closest(token.value, DECLARATION_KEYWORDS)
        report(
            code = SyntaxCode.EXPECTED_DECLARATION,
            span = token.span,
            message = "unknown top-level declaration `${token.value}`",
            label = "expected `model`, `enum`, `datasource` or `generator`",
            help = suggestion?.let { "did you mean `$it`?" }
                ?: "a schema contains only `datasource`, `generator`, `model` and `enum` blocks",
        )
    }

    private fun reportUnexpectedTopLevelToken(token: Token) {
        report(
            code = SyntaxCode.UNEXPECTED_TOP_LEVEL_TOKEN,
            span = token.span,
            message = "expected a top-level declaration, found ${describe(token)}",
            label = "expected `model`, `enum`, `datasource` or `generator`",
        )
    }

    private fun reportUnexpectedTokenInBlock(token: Token, expectation: String) {
        report(
            code = SyntaxCode.UNEXPECTED_TOKEN_IN_BLOCK,
            span = token.span,
            message = "unexpected ${describe(token)}",
            label = "expected $expectation",
        )
    }

    private fun peek(offset: Int = 0): Token = tokens[minOf(index + offset, tokens.size - 1)]

    private fun previous(): Token = tokens[maxOf(index - 1, 0)]

    private fun check(type: TokenType): Boolean = peek().type == type

    private fun advance(): Token {
        val token = peek()
        if (index < tokens.size - 1) index++
        return token
    }

    private fun canStartExpression(token: Token): Boolean = when (token.type) {
        TokenType.STRING, TokenType.NUMBER, TokenType.IDENTIFIER, TokenType.LEFT_BRACKET -> true
        else -> false
    }

    private fun describe(token: Token): String = when (token.type) {
        TokenType.IDENTIFIER -> "`${token.text}`"
        TokenType.STRING -> "string `${token.text}`"
        TokenType.NUMBER -> "number `${token.text}`"
        TokenType.END_OF_FILE -> "the end of the file"
        TokenType.LINE_COMMENT, TokenType.DOC_COMMENT -> "a comment"
        else -> token.type.display
    }

    private fun report(code: DiagnosticCode, span: SourceSpan, message: String, label: String? = null, help: String? = null) {
        diagnostics.add(Diagnostic(Severity.ERROR, code, message, span, label, help))
    }

    private companion object {
        private val DECLARATION_KEYWORDS = listOf("datasource", "generator", "model", "enum")
    }
}
