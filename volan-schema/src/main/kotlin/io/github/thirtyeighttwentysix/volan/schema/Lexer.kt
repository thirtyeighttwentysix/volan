package io.github.thirtyeighttwentysix.volan.schema

/** The tokens of a document together with every problem found while producing them. */
internal class LexResult(val tokens: List<Token>, val diagnostics: List<Diagnostic>)

/**
 * Turns schema text into tokens.
 *
 * The lexer never throws and never stops early: a malformed string or an unexpected character is
 * reported as a diagnostic and lexing continues, so that a single typo does not hide every other
 * problem in the file.
 */
internal class Lexer(private val source: SourceFile) {
    private val text: String = source.text
    private var offset: Int = 0
    private var pendingNewlines: Int = 0
    private val tokens = ArrayList<Token>()
    private val diagnostics = ArrayList<Diagnostic>()

    fun tokenize(): LexResult {
        while (true) {
            skipWhitespace()
            if (offset >= text.length) break
            val before = offset
            readToken()
            check(offset > before) { "lexer made no progress at offset $before in ${source.name}" }
        }
        addToken(TokenType.END_OF_FILE, text.length, text.length, "")
        return LexResult(tokens, diagnostics)
    }

    private fun readToken() {
        val current = text[offset]
        when {
            current == '/' -> readSlash()
            current == '"' -> readString()
            current.isDigit() || (current == '-' && charAt(offset + 1)?.isDigit() == true) -> readNumber()
            isIdentifierStart(current) -> readIdentifier()
            else -> readSymbol()
        }
    }

    private fun readIdentifier() {
        val start = offset
        while (offset < text.length && isIdentifierPart(text[offset])) offset++
        addToken(TokenType.IDENTIFIER, start, offset, text.substring(start, offset))
    }

    private fun readNumber() {
        val start = offset
        if (text[offset] == '-') offset++
        consumeDigits()
        if (charAt(offset) == '.' && charAt(offset + 1)?.isDigit() == true) {
            offset++
            consumeDigits()
        }
        if (charAt(offset) == '.' && charAt(offset + 1)?.isDigit() == true) {
            while (offset < text.length && (text[offset].isDigit() || text[offset] == '.')) offset++
            report(
                code = SyntaxCode.MALFORMED_NUMBER,
                span = SourceSpan(start, offset),
                message = "malformed number literal `${text.substring(start, offset)}`",
                label = "a number has at most one decimal point",
            )
        }
        addToken(TokenType.NUMBER, start, offset, text.substring(start, offset))
    }

    private fun consumeDigits() {
        while (offset < text.length && text[offset].isDigit()) offset++
    }

    private fun readString() {
        val start = offset
        offset++
        val decoded = StringBuilder()
        var terminated = false
        while (offset < text.length) {
            val current = text[offset]
            if (current == '\n') break
            if (current == '"') {
                offset++
                terminated = true
                break
            }
            if (current == '\\') {
                readEscape(decoded)
            } else {
                decoded.append(current)
                offset++
            }
        }
        if (!terminated) {
            report(
                code = SyntaxCode.UNTERMINATED_STRING,
                span = SourceSpan(start, offset),
                message = "unterminated string literal",
                label = "the closing `\"` is missing",
                help = "strings may not span lines; add a closing `\"` before the end of the line",
            )
        }
        addToken(TokenType.STRING, start, offset, text.substring(start, offset), decoded.toString())
    }

    private fun readEscape(decoded: StringBuilder) {
        val start = offset
        offset++
        val escaped = charAt(offset)
        val simple = when (escaped) {
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            '\\' -> '\\'
            '"' -> '"'
            '/' -> '/'
            else -> null
        }
        when {
            simple != null -> {
                decoded.append(simple)
                offset++
            }
            escaped == 'u' -> readUnicodeEscape(decoded, start)
            else -> {
                report(
                    code = SyntaxCode.INVALID_ESCAPE_SEQUENCE,
                    span = SourceSpan(start, minOf(start + 2, text.length)),
                    message = "invalid escape sequence in string literal",
                    label = "this is not an escape the schema language knows",
                    help = "the supported escapes are \\n, \\r, \\t, \\\", \\\\, \\/ and \\uXXXX",
                )
                decoded.append('\\')
                if (escaped != null) {
                    decoded.append(escaped)
                    offset++
                }
            }
        }
    }

    private fun readUnicodeEscape(decoded: StringBuilder, start: Int) {
        val digitsStart = offset + 1
        val digitsEnd = digitsStart + UNICODE_ESCAPE_DIGITS
        val digits = if (digitsEnd <= text.length) text.substring(digitsStart, digitsEnd) else null
        val code = digits?.toIntOrNull(radix = HEX_RADIX)
        if (code == null) {
            report(
                code = SyntaxCode.INVALID_ESCAPE_SEQUENCE,
                span = SourceSpan(start, minOf(digitsEnd, text.length)),
                message = "invalid unicode escape sequence",
                label = "expected exactly four hexadecimal digits",
                help = "write a unicode escape as \\uXXXX, for example \\u00e9",
            )
            decoded.append("\\u")
            offset += 2
            return
        }
        decoded.append(code.toChar())
        offset = digitsEnd
    }

    private fun readSlash() {
        when (charAt(offset + 1)) {
            '/' -> readLineComment()
            '*' -> readBlockComment()
            else -> readSymbol()
        }
    }

    private fun readLineComment() {
        val start = offset
        val isDoc = charAt(offset + 2) == '/'
        offset += if (isDoc) DOC_COMMENT_MARKER_LENGTH else LINE_COMMENT_MARKER_LENGTH
        val contentStart = offset
        while (offset < text.length && text[offset] != '\n') offset++
        val type = if (isDoc) TokenType.DOC_COMMENT else TokenType.LINE_COMMENT
        addToken(type, start, offset, text.substring(start, offset), text.substring(contentStart, offset).trim())
    }

    private fun readBlockComment() {
        val start = offset
        offset += 2
        while (offset < text.length) {
            if (text[offset] == '*' && charAt(offset + 1) == '/') {
                offset += 2
                break
            }
            offset++
        }
        report(
            code = SyntaxCode.BLOCK_COMMENT_NOT_SUPPORTED,
            span = SourceSpan(start, offset),
            message = "block comments are not supported",
            label = "the schema language only has line comments",
            help = "use `//` for a comment, or `///` to document the declaration below it",
        )
    }

    private fun readSymbol() {
        val start = offset
        val current = text[offset]
        val type = symbolType(current)
        if (type == null) {
            offset++
            report(
                code = SyntaxCode.UNEXPECTED_CHARACTER,
                span = SourceSpan(start, offset),
                message = "unexpected character `$current`",
                label = "this character has no meaning in a schema",
            )
            return
        }
        offset += if (type == TokenType.AT_AT) 2 else 1
        addToken(type, start, offset, text.substring(start, offset))
    }

    private fun symbolType(current: Char): TokenType? = when (current) {
        '@' -> if (charAt(offset + 1) == '@') TokenType.AT_AT else TokenType.AT
        '{' -> TokenType.LEFT_BRACE
        '}' -> TokenType.RIGHT_BRACE
        '(' -> TokenType.LEFT_PAREN
        ')' -> TokenType.RIGHT_PAREN
        '[' -> TokenType.LEFT_BRACKET
        ']' -> TokenType.RIGHT_BRACKET
        ',' -> TokenType.COMMA
        ':' -> TokenType.COLON
        '=' -> TokenType.EQUALS
        '?' -> TokenType.QUESTION
        '.' -> TokenType.DOT
        else -> null
    }

    private fun skipWhitespace() {
        while (offset < text.length) {
            when (text[offset]) {
                '\n' -> {
                    pendingNewlines++
                    offset++
                }
                ' ', '\t', '\r', '\u000C' -> offset++
                else -> return
            }
        }
    }

    private fun addToken(type: TokenType, start: Int, end: Int, raw: String, value: String = raw) {
        tokens.add(
            Token(
                type = type,
                span = SourceSpan(start, end),
                text = raw,
                value = value,
                newlineBefore = pendingNewlines > 0 || tokens.isEmpty(),
                blankLineBefore = pendingNewlines > 1,
            ),
        )
        pendingNewlines = 0
    }

    private fun report(code: DiagnosticCode, span: SourceSpan, message: String, label: String? = null, help: String? = null) {
        diagnostics.add(Diagnostic(Severity.ERROR, code, message, span, label, help))
    }

    private fun charAt(index: Int): Char? = if (index in text.indices) text[index] else null

    private fun isIdentifierStart(current: Char): Boolean = current.isLetter() || current == '_'

    private fun isIdentifierPart(current: Char): Boolean = current.isLetterOrDigit() || current == '_'

    private companion object {
        private const val HEX_RADIX = 16
        private const val UNICODE_ESCAPE_DIGITS = 4
        private const val LINE_COMMENT_MARKER_LENGTH = 2
        private const val DOC_COMMENT_MARKER_LENGTH = 3
    }
}
