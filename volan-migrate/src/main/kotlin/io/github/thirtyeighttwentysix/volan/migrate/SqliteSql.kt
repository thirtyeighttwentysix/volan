package io.github.thirtyeighttwentysix.volan.migrate

/** Tokens retain SQL literals separately from identifiers, including escaped quotes and comments. */
internal data class SqliteToken(
    val text: String,
    val value: String,
    val quoted: Boolean = false,
    val literal: Boolean = false,
) {
    fun keyword(word: String): Boolean = !quoted && !literal && value.equals(word, ignoreCase = true)
}

internal object SqliteSql {
    fun tokens(sql: String): List<SqliteToken> = SqliteTokenizer(sql).scan()

    fun normalize(sql: String): String = tokens(sql).joinToString(" ") { if (it.quoted) it.text else it.text.lowercase() }

    fun groups(tokens: List<SqliteToken>): List<List<SqliteToken>> {
        val groups = ArrayList<List<SqliteToken>>()
        var depth = 0
        var start = 0
        tokens.forEachIndexed { index, token ->
            when (token.text) {
                "(" -> depth++
                ")" -> depth--
                "," -> if (depth == 0) {
                    groups += tokens.subList(start, index)
                    start = index + 1
                }
            }
            if (depth < 0) unsupported("unbalanced SQL parentheses")
        }
        if (depth != 0) unsupported("unbalanced SQL parentheses")
        groups += tokens.subList(start, tokens.size)
        return groups
    }

    fun unsupported(detail: String): Nothing = throw VolanMigrationException(
        "SQLite definition cannot be preserved by Volan: $detail. Use an explicit migration for this database.",
    )
}

internal class SqliteCursor(private val tokens: List<SqliteToken>) {
    private var offset = 0
    val done: Boolean get() = offset == tokens.size
    fun peek(): SqliteToken? = tokens.getOrNull(offset)
    fun take(): SqliteToken = tokens.getOrNull(offset++) ?: SqliteSql.unsupported("incomplete SQL declaration")
    fun accept(word: String): Boolean = if (peek()?.keyword(word) == true) {
        offset++
        true
    } else {
        false
    }
    fun expect(word: String) {
        if (!accept(word)) SqliteSql.unsupported("expected $word")
    }
    fun names(): List<String> {
        expect("(")
        val names = ArrayList<String>()
        do {
            val token = take()
            if (token.literal || token.text in listOf(")", ",", "(")) SqliteSql.unsupported("invalid constraint column")
            names += token.value
        } while (accept(","))
        expect(")")
        return names
    }
    fun remaining(): List<SqliteToken> = tokens.drop(offset).also { offset = tokens.size }
}

private class SqliteTokenizer(private val sql: String) {
    private var offset = 0
    private val result = ArrayList<SqliteToken>()

    fun scan(): List<SqliteToken> {
        while (offset < sql.length) {
            val character = sql[offset]
            when {
                character.isWhitespace() -> offset++
                sql.startsWith("--", offset) -> offset = sql.indexOf('\n', offset).takeIf { it >= 0 } ?: sql.length
                sql.startsWith("/*", offset) -> comment()
                sql.regionMatches(offset, "x'", 0, 2, ignoreCase = true) -> blob()
                character in listOf('\'', '"', '`', '[') -> quoted(character)
                number.matchesAt(sql, offset) -> number()
                character.isLetterOrDigit() || character == '_' -> word()
                else -> symbol()
            }
        }
        return result
    }

    private fun number() {
        val value = requireNotNull(number.matchAt(sql, offset)).value
        result += SqliteToken(value, value)
        offset += value.length
    }

    private fun blob() {
        val start = offset++
        quoted('\'')
        val contents = result.removeLast()
        result += SqliteToken(sql.substring(start, offset), contents.value, quoted = true)
    }

    private fun symbol() {
        val value = OPERATORS.firstOrNull { sql.startsWith(it, offset) } ?: sql[offset].toString()
        result += SqliteToken(value, value)
        offset += value.length
    }

    private fun comment() {
        val end = sql.indexOf("*/", offset + 2)
        if (end < 0) SqliteSql.unsupported("unterminated SQL comment")
        if (sql.substring(offset + 2, end).trim() == "volan:Long") {
            result += SqliteToken("/* volan:Long */", "volan:Long", quoted = true)
        }
        offset = end + 2
    }

    private fun word() {
        val start = offset++
        while (offset < sql.length && (sql[offset].isLetterOrDigit() || sql[offset] == '_')) offset++
        result += SqliteToken(sql.substring(start, offset), sql.substring(start, offset))
    }

    private fun quoted(character: Char) {
        val endQuote = if (character == '[') ']' else character
        val start = offset++
        val value = StringBuilder()
        var closed = false
        while (offset < sql.length) {
            val next = sql[offset++]
            if (next != endQuote) {
                value.append(next)
            } else if (character != '[' && offset < sql.length && sql[offset] == endQuote) {
                value.append(endQuote)
                offset++
            } else {
                closed = true
                break
            }
        }
        if (!closed) SqliteSql.unsupported("unterminated SQL quote")
        result += SqliteToken(sql.substring(start, offset), value.toString(), quoted = true, literal = character == '\'')
    }

    private companion object {
        val number = Regex("0[xX][0-9a-fA-F]+|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        val OPERATORS = listOf("->>", "->", "||", "<=", ">=", "==", "!=", "<>")
    }
}
