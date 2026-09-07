package io.github.thirtyeighttwentysix.volan.migrate

/** PostgreSQL scripts may contain function bodies, nested comments and quoted semicolons. */
internal class SqlScript(private val sql: String) {
    private var index = 0
    private val current = StringBuilder()
    private val statements = ArrayList<String>()

    fun split(): List<String> {
        while (index < sql.length) {
            when {
                sql.startsWith("--", index) -> lineComment()
                sql.startsWith("/*", index) -> blockComment()
                sql[index] == '\'' || sql[index] == '"' -> quoted()
                sql[index] == '$' && dollarTag() != null -> dollarQuoted(requireNotNull(dollarTag()))
                sql[index] == ';' -> {
                    finish()
                    index++
                }
                else -> current.append(sql[index++])
            }
        }
        finish()
        return statements
    }

    private fun finish() {
        current.toString().trim().takeIf { it.isNotEmpty() }?.let { statements.add(it) }
        current.setLength(0)
    }

    private fun lineComment() {
        index = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length
        current.append(' ')
    }

    private fun blockComment() {
        var depth = 1
        index += 2
        while (index < sql.length && depth > 0) {
            when {
                sql.startsWith("/*", index) -> {
                    depth++
                    index += 2
                }
                sql.startsWith("*/", index) -> {
                    depth--
                    index += 2
                }
                else -> index++
            }
        }
        if (depth != 0) throw VolanMigrationException("Unterminated block comment in migration SQL.")
        current.append(' ')
    }

    private fun quoted() {
        val quote = sql[index]
        val escaped = quote == '\'' && index > 0 && sql[index - 1].equals('e', ignoreCase = true) &&
            (index < 2 || !sql[index - 2].isLetterOrDigit())
        current.append(sql[index++])
        while (index < sql.length) {
            val character = sql[index++]
            current.append(character)
            if (escaped && character == '\\' && index < sql.length) {
                current.append(sql[index++])
            } else if (character == quote) {
                if (index < sql.length && sql[index] == quote) current.append(sql[index++]) else return
            }
        }
        throw VolanMigrationException("Unterminated quoted literal or identifier in migration SQL.")
    }

    private fun dollarTag(): String? {
        if (index > 0 && (sql[index - 1].isLetterOrDigit() || sql[index - 1] == '_')) return null
        return DOLLAR_TAG.find(sql, index)?.takeIf { it.range.first == index }?.value
    }

    private fun dollarQuoted(tag: String) {
        val end = sql.indexOf(tag, index + tag.length)
        if (end < 0) throw VolanMigrationException("Unterminated dollar-quoted body in migration SQL.")
        current.append(sql.substring(index, end + tag.length))
        index = end + tag.length
    }

    private companion object {
        private val DOLLAR_TAG = Regex("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$")
    }
}
