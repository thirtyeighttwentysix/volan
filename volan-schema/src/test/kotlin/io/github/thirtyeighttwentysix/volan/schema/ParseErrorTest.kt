package io.github.thirtyeighttwentysix.volan.schema

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every case below is a mistake a user can plausibly make. Each asserts the diagnostic code and the
 * wording the user sees, because the wording is the feature.
 */
class ParseErrorTest {
    private data class Case(
        val name: String,
        val schema: String,
        val code: DiagnosticCode,
        val message: String,
        val help: String? = null,
    )

    private val cases = listOf(
        Case(
            name = "unterminated string",
            schema = "datasource db {\n  provider = \"postgres\n}\n",
            code = SyntaxCode.UNTERMINATED_STRING,
            message = "unterminated string literal",
            help = "strings may not span lines",
        ),
        Case(
            name = "invalid escape sequence",
            schema = "datasource db {\n  url = \"postgres:\\q//localhost\"\n}\n",
            code = SyntaxCode.INVALID_ESCAPE_SEQUENCE,
            message = "invalid escape sequence in string literal",
            help = "the supported escapes are",
        ),
        Case(
            name = "invalid unicode escape",
            schema = "datasource db {\n  url = \"\\u12\"\n}\n",
            code = SyntaxCode.INVALID_ESCAPE_SEQUENCE,
            message = "invalid unicode escape sequence",
            help = "write a unicode escape as",
        ),
        Case(
            name = "unexpected character",
            schema = "model User {\n  id Int #\n}\n",
            code = SyntaxCode.UNEXPECTED_CHARACTER,
            message = "unexpected character `#`",
        ),
        Case(
            name = "block comment",
            schema = "/* a model */\nmodel User {\n  id Int @id\n}\n",
            code = SyntaxCode.BLOCK_COMMENT_NOT_SUPPORTED,
            message = "block comments are not supported",
            help = "use `//` for a comment",
        ),
        Case(
            name = "malformed number",
            schema = "model User {\n  id Int @default(1.2.3)\n}\n",
            code = SyntaxCode.MALFORMED_NUMBER,
            message = "malformed number literal `1.2.3`",
        ),
        Case(
            name = "misspelled declaration keyword",
            schema = "mdoel User {\n  id Int @id\n}\n",
            code = SyntaxCode.EXPECTED_DECLARATION,
            message = "unknown top-level declaration `mdoel`",
            help = "did you mean `model`?",
        ),
        Case(
            name = "unknown declaration keyword",
            schema = "table User {\n  id Int @id\n}\n",
            code = SyntaxCode.EXPECTED_DECLARATION,
            message = "unknown top-level declaration `table`",
            help = "a schema contains only `datasource`, `generator`, `model` and `enum` blocks",
        ),
        Case(
            name = "missing model name",
            schema = "model {\n  id Int @id\n}\n",
            code = SyntaxCode.EXPECTED_NAME,
            message = "expected a name for the model",
            help = "a model is written as `model Name { … }`",
        ),
        Case(
            name = "quoted model name",
            schema = "model \"User\" {\n  id Int @id\n}\n",
            code = SyntaxCode.EXPECTED_NAME,
            message = "expected a name for the model",
            help = "names are written without quotes",
        ),
        Case(
            name = "missing opening brace",
            schema = "model User\n  id Int @id\n",
            code = SyntaxCode.EXPECTED_OPENING_BRACE,
            message = "expected `{` after `model User`",
        ),
        Case(
            name = "unclosed model block",
            schema = "model User {\n  id Int @id\n",
            code = SyntaxCode.UNCLOSED_BLOCK,
            message = "unclosed model `User`",
            help = "add a `}` to close `model User`",
        ),
        Case(
            name = "unclosed datasource block",
            schema = "datasource db {\n  provider = \"postgresql\"\n",
            code = SyntaxCode.UNCLOSED_BLOCK,
            message = "unclosed datasource `db`",
        ),
        Case(
            name = "field without a type",
            schema = "model User {\n  email @unique\n}\n",
            code = SyntaxCode.EXPECTED_FIELD_TYPE,
            message = "expected a field type",
            help = "a field is written as `name Type`, for example `email String`",
        ),
        Case(
            name = "optional list type",
            schema = "model User {\n  posts Post[]?\n}\n",
            code = SyntaxCode.OPTIONAL_LIST_TYPE,
            message = "a list type cannot be optional",
            help = "an empty list already means `no values`",
        ),
        Case(
            name = "nested list type",
            schema = "model User {\n  posts Post[][]\n}\n",
            code = SyntaxCode.NESTED_LIST_TYPE,
            message = "nested list types are not supported",
        ),
        Case(
            name = "repeated optional marker",
            schema = "model User {\n  name String??\n}\n",
            code = SyntaxCode.REPEATED_OPTIONAL_MARKER,
            message = "a type carries at most one `?`",
        ),
        Case(
            name = "unclosed list type",
            schema = "model User {\n  posts Post[\n}\n",
            code = SyntaxCode.UNCLOSED_LIST,
            message = "expected `]` to close the list type",
        ),
        Case(
            name = "block attribute written with one at sign",
            schema = "model User {\n  id Int @id\n  @index([id])\n}\n",
            code = SyntaxCode.WRONG_ATTRIBUTE_FORM,
            message = "attributes that apply to the whole block are written with `@@`",
        ),
        Case(
            name = "field attribute written with two at signs",
            schema = "model User {\n  id Int @@id\n}\n",
            code = SyntaxCode.WRONG_ATTRIBUTE_FORM,
            message = "field attributes are written with a single `@`",
        ),
        Case(
            name = "missing attribute name",
            schema = "model User {\n  id Int @\n}\n",
            code = SyntaxCode.EXPECTED_ATTRIBUTE_NAME,
            message = "expected an attribute name",
        ),
        Case(
            name = "missing native type member",
            schema = "model User {\n  name String @db.\n}\n",
            code = SyntaxCode.EXPECTED_ATTRIBUTE_MEMBER,
            message = "expected a name after `db.`",
            help = "native type attributes are written as `@db.VarChar(200)`",
        ),
        Case(
            name = "unclosed argument list",
            schema = "model User {\n  id Int @default(now()\n}\n",
            code = SyntaxCode.UNCLOSED_ARGUMENT_LIST,
            message = "expected `)` to close the argument list",
        ),
        Case(
            name = "named argument without a value",
            schema = "model Post {\n  author User @relation(fields:)\n}\n",
            code = SyntaxCode.EXPECTED_ARGUMENT_VALUE,
            message = "named argument `fields` has no value",
        ),
        Case(
            name = "unclosed list literal",
            schema = "model User {\n  id Int @id\n  @@index([id)\n}\n",
            code = SyntaxCode.UNCLOSED_LIST,
            message = "expected `]` to close the list",
        ),
        Case(
            name = "configuration property without equals",
            schema = "datasource db {\n  provider \"postgresql\"\n}\n",
            code = SyntaxCode.EXPECTED_EQUALS,
            message = "expected `=` after `provider`",
            help = "configuration properties are written as `name = value`",
        ),
        Case(
            name = "configuration property without a value",
            schema = "datasource db {\n  url =\n}\n",
            code = SyntaxCode.EXPECTED_CONFIGURATION_VALUE,
            message = "expected a value for `url`",
        ),
        Case(
            name = "attribute inside a datasource block",
            schema = "datasource db {\n  @@map(\"x\")\n}\n",
            code = SyntaxCode.ATTRIBUTE_NOT_ALLOWED_HERE,
            message = "attributes are not allowed in a `datasource` block",
        ),
        Case(
            name = "stray closing brace at the top level",
            schema = "}\nmodel User {\n  id Int @id\n}\n",
            code = SyntaxCode.UNEXPECTED_TOP_LEVEL_TOKEN,
            message = "expected a top-level declaration, found `}`",
        ),
        Case(
            name = "unexpected token inside a model",
            schema = "model User {\n  id Int @id\n  = 1\n}\n",
            code = SyntaxCode.UNEXPECTED_TOKEN_IN_BLOCK,
            message = "unexpected `=`",
        ),
        Case(
            name = "unexpected token inside an enum",
            schema = "enum Role {\n  USER\n  42\n}\n",
            code = SyntaxCode.UNEXPECTED_TOKEN_IN_BLOCK,
            message = "unexpected number `42`",
        ),
        Case(
            name = "model without fields",
            schema = "model User {\n}\n",
            code = SyntaxCode.EMPTY_MODEL,
            message = "model `User` declares no fields",
            help = "add a field, for example `id Int @id @default(autoincrement())`",
        ),
        Case(
            name = "enum without values",
            schema = "enum Role {\n}\n",
            code = SyntaxCode.EMPTY_ENUM,
            message = "enum `Role` declares no values",
        ),
        Case(
            name = "value expected in an attribute argument",
            schema = "model User {\n  id Int @default(=)\n}\n",
            code = SyntaxCode.EXPECTED_EXPRESSION,
            message = "expected a value",
        ),
    )

    @TestFactory
    fun `every mistake is reported with its code, message and help`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.name) {
            val result = SchemaParser.parse("schema.volan", case.schema)
            val rendered = result.render()
            withClue(rendered.ifEmpty { "no diagnostics were reported" }) {
                result.diagnostics.map { it.code } shouldContain case.code
                rendered shouldContain case.message
                case.help?.let { rendered shouldContain it }
            }
        }
    }

    @Test
    fun `the negative corpus covers at least thirty distinct mistakes`() {
        cases.map { it.name }.distinct().size shouldBe cases.size
        cases.size shouldBeGreaterThanOrEqual MINIMUM_NEGATIVE_CASES
    }

    @Test
    fun `a broken schema still yields a document, so tooling can keep working`() {
        cases.forEach { case ->
            withClue(case.name) {
                val result = SchemaParser.parse("schema.volan", case.schema)
                result.hasErrors shouldBe true
                result.render().isNotEmpty() shouldBe true
            }
        }
    }

    @Test
    fun `recovery reports every mistake in a file rather than only the first`() {
        val schema = """
            model User {
              email @unique
              name String??
              posts Post[]?
            }
        """.trimIndent()
        val codes = SchemaParser.parse("schema.volan", schema).diagnostics.map { it.code }
        codes shouldContain SyntaxCode.EXPECTED_FIELD_TYPE
        codes shouldContain SyntaxCode.REPEATED_OPTIONAL_MARKER
        codes shouldContain SyntaxCode.OPTIONAL_LIST_TYPE
    }

    @Test
    fun `a diagnostic renders as a code frame with a caret and a suggestion`() {
        val result = SchemaParser.parse("schema.volan", "model User {\n  email @unique\n}\n")
        result.render() shouldBe
            """
            error[E0104]: expected a field type
             ┌─ schema.volan:2:9
             │
            2│  email @unique
             │        ^ found `@`
             │
             = help: a field is written as `name Type`, for example `email String`

            1 error found in schema.volan

            """.trimIndent()
    }

    @Test
    fun `documentOrThrow reports every diagnostic in its message`() {
        val result = SchemaParser.parse("schema.volan", "model User {\n  email @unique\n}\n")
        val thrown = runCatching { result.documentOrThrow() }.exceptionOrNull()
        withClue("documentOrThrow must fail for a schema with errors") {
            (thrown is VolanSchemaException) shouldBe true
        }
        (thrown as VolanSchemaException).diagnostics.size shouldBe result.diagnostics.size
        thrown.message.orEmpty() shouldContain "expected a field type"
    }

    private companion object {
        private const val MINIMUM_NEGATIVE_CASES = 30
    }
}
