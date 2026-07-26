package io.github.thirtyeighttwentysix.volan.schema

/**
 * The kinds of token the schema language is built from.
 *
 * The language has no keywords: `model`, `enum`, `datasource` and `generator` are ordinary
 * identifiers that the parser recognises by position. That is what lets a field be called `model`
 * without any escaping, and it keeps "unknown declaration" errors able to suggest a correction.
 */
internal enum class TokenType(val display: String) {
    IDENTIFIER("a name"),
    STRING("a string"),
    NUMBER("a number"),
    AT("`@`"),
    AT_AT("`@@`"),
    LEFT_BRACE("`{`"),
    RIGHT_BRACE("`}`"),
    LEFT_PAREN("`(`"),
    RIGHT_PAREN("`)`"),
    LEFT_BRACKET("`[`"),
    RIGHT_BRACKET("`]`"),
    COMMA("`,`"),
    COLON("`:`"),
    EQUALS("`=`"),
    QUESTION("`?`"),
    DOT("`.`"),
    LINE_COMMENT("a comment"),
    DOC_COMMENT("a documentation comment"),
    END_OF_FILE("the end of the file"),
}

/**
 * One lexical token.
 *
 * @property type what kind of token this is.
 * @property span the exact source range it covers.
 * @property text the raw source text, exactly as written.
 * @property value the decoded value: escapes resolved for strings, comment markers stripped for
 *   comments, and identical to [text] for everything else.
 * @property newlineBefore whether at least one line break separates this token from the previous one.
 * @property blankLineBefore whether at least one entirely blank line separates it from the previous one.
 */
internal class Token(
    val type: TokenType,
    val span: SourceSpan,
    val text: String,
    val value: String,
    val newlineBefore: Boolean,
    val blankLineBefore: Boolean,
) {
    /** True for the two token types that carry comment text. */
    val isComment: Boolean
        get() = type == TokenType.LINE_COMMENT || type == TokenType.DOC_COMMENT

    override fun toString(): String = "${type.name}('$text' at $span)"
}
