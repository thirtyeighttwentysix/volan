package io.github.thirtyeighttwentysix.volan.schema

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LexerTest {
    private fun tokenize(text: String): LexResult = Lexer(SourceFile("schema.volan", text)).tokenize()

    private fun types(text: String): List<TokenType> = tokenize(text).tokens.map { it.type }

    @Test
    fun `recognises the punctuation of the language`() {
        types("@ @@ { } ( ) [ ] , : = ? .") shouldContainExactly listOf(
            TokenType.AT,
            TokenType.AT_AT,
            TokenType.LEFT_BRACE,
            TokenType.RIGHT_BRACE,
            TokenType.LEFT_PAREN,
            TokenType.RIGHT_PAREN,
            TokenType.LEFT_BRACKET,
            TokenType.RIGHT_BRACKET,
            TokenType.COMMA,
            TokenType.COLON,
            TokenType.EQUALS,
            TokenType.QUESTION,
            TokenType.DOT,
            TokenType.END_OF_FILE,
        )
    }

    @Test
    fun `decodes string escapes`() {
        val token = tokenize("\"a\\nb\\t\\\"c\\\\d\\u0041\"").tokens.first()
        token.type shouldBe TokenType.STRING
        token.value shouldBe "a\nb\t\"c\\dA"
    }

    @Test
    fun `keeps the raw text of a string alongside its decoded value`() {
        val token = tokenize("\"a\\nb\"").tokens.first()
        token.text shouldBe "\"a\\nb\""
        token.value shouldBe "a\nb"
    }

    @Test
    fun `reads negative and fractional numbers as single tokens`() {
        types("-1 2.5") shouldContainExactly listOf(TokenType.NUMBER, TokenType.NUMBER, TokenType.END_OF_FILE)
        tokenize("-1").tokens.first().text shouldBe "-1"
    }

    @Test
    fun `separates line comments from documentation comments`() {
        val tokens = tokenize("// plain\n/// documented\n").tokens
        tokens.map { it.type } shouldContainExactly listOf(TokenType.LINE_COMMENT, TokenType.DOC_COMMENT, TokenType.END_OF_FILE)
        tokens[0].value shouldBe "plain"
        tokens[1].value shouldBe "documented"
    }

    @Test
    fun `marks tokens that follow a line break and those that follow a blank line`() {
        val tokens = tokenize("a b\nc\n\nd").tokens
        tokens.map { it.newlineBefore } shouldContainExactly listOf(true, false, true, true, false)
        tokens.map { it.blankLineBefore } shouldContainExactly listOf(false, false, false, true, false)
    }

    @Test
    fun `always ends with an end of file token spanning no characters`() {
        val tokens = tokenize("model").tokens
        tokens.last().type shouldBe TokenType.END_OF_FILE
        tokens.last().span.length shouldBe 0
    }

    @Test
    fun `keeps lexing after an unexpected character`() {
        val result = tokenize("a # b")
        result.diagnostics.single().code shouldBe SyntaxCode.UNEXPECTED_CHARACTER
        result.tokens.map { it.type } shouldContainExactly
            listOf(TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.END_OF_FILE)
    }

    @Test
    fun `spans cover exactly the token text`() {
        val text = "model User"
        val token = tokenize(text).tokens[1]
        text.substring(token.span.start, token.span.end) shouldBe "User"
    }
}
