package io.github.thirtyeighttwentysix.volan.schema

import io.github.thirtyeighttwentysix.volan.schema.ast.ArrayLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.BlockAttributeDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.BooleanLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.ConstantReference
import io.github.thirtyeighttwentysix.volan.schema.ast.FunctionCall
import io.github.thirtyeighttwentysix.volan.schema.ast.NumberLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.StringLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.TypeArity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SchemaParserTest {
    private val result = SchemaParser.parse(Fixtures.source("reference.volan"))
    private val document = result.document

    @Test
    fun `the reference schema parses without diagnostics`() {
        result.render() shouldBe ""
        result.hasErrors shouldBe false
    }

    @Test
    fun `declarations are kept in source order`() {
        document.declarations.map { it.name.text } shouldContainExactly listOf("db", "client", "Role", "User", "Post")
    }

    @Test
    fun `datasource entries are parsed as key-value pairs`() {
        val datasource = document.datasources.single()
        datasource.entries.map { it.key.text } shouldContainExactly listOf("provider", "url")
        datasource.entries[0].value.shouldBeInstanceOf<StringLiteral>().value shouldBe "postgresql"
        val url = datasource.entries[1].value.shouldBeInstanceOf<FunctionCall>()
        url.name.text shouldBe "env"
        url.arguments.single().value.shouldBeInstanceOf<StringLiteral>().value shouldBe "DATABASE_URL"
    }

    @Test
    fun `generator booleans are parsed as booleans`() {
        val generator = document.generators.single()
        val javaFriendly = generator.entries.single { it.key.text == "javaFriendly" }
        javaFriendly.value.shouldBeInstanceOf<BooleanLiteral>().value shouldBe true
    }

    @Test
    fun `enum values are parsed`() {
        val role = document.enums.single()
        role.values.map { it.name.text } shouldContainExactly listOf("USER", "ADMIN")
    }

    @Test
    fun `field arity distinguishes required optional and list`() {
        val user = document.models.single { it.name.text == "User" }
        val arities = user.fields.associate { it.name.text to it.type.arity }
        arities["id"] shouldBe TypeArity.REQUIRED
        arities["name"] shouldBe TypeArity.OPTIONAL
        arities["profile"] shouldBe TypeArity.OPTIONAL
        arities["posts"] shouldBe TypeArity.LIST
    }

    @Test
    fun `field attributes keep their arguments`() {
        val user = document.models.single { it.name.text == "User" }
        val id = user.fields.single { it.name.text == "id" }
        id.attributes.map { it.name.qualifiedName } shouldContainExactly listOf("id", "default")
        val default = id.attributes[1].arguments.single().value.shouldBeInstanceOf<FunctionCall>()
        default.name.text shouldBe "autoincrement"
        default.arguments.size shouldBe 0
    }

    @Test
    fun `enum defaults are parsed as constant references`() {
        val user = document.models.single { it.name.text == "User" }
        val role = user.fields.single { it.name.text == "role" }
        role.attributes.single().arguments.single().value.shouldBeInstanceOf<ConstantReference>().name.text shouldBe "USER"
    }

    @Test
    fun `namespaced native type attributes keep their namespace`() {
        val post = document.models.single { it.name.text == "Post" }
        val title = post.fields.single { it.name.text == "title" }
        val native = title.attributes.single()
        native.name.namespace.shouldNotBeNull().text shouldBe "db"
        native.name.name.text shouldBe "VarChar"
        native.name.qualifiedName shouldBe "db.VarChar"
        native.arguments.single().value.shouldBeInstanceOf<NumberLiteral>().text shouldBe "200"
    }

    @Test
    fun `relation attributes keep named arguments in order`() {
        val post = document.models.single { it.name.text == "Post" }
        val author = post.fields.single { it.name.text == "author" }
        val relation = author.attributes.single()
        relation.arguments.map { it.name?.text } shouldContainExactly listOf("fields", "references", "onDelete")
        val fields = relation.arguments[0].value.shouldBeInstanceOf<ArrayLiteral>()
        fields.elements.map { it.shouldBeInstanceOf<ConstantReference>().name.text } shouldContainExactly listOf("authorId")
        relation.arguments[2].value.shouldBeInstanceOf<ConstantReference>().name.text shouldBe "Cascade"
    }

    @Test
    fun `block attributes are parsed and kept in source order`() {
        val user = document.models.single { it.name.text == "User" }
        user.attributes.map { it.name.qualifiedName } shouldContainExactly listOf("index", "map")
        val index = user.attributes[0].arguments.single().value.shouldBeInstanceOf<ArrayLiteral>()
        index.elements.map { it.shouldBeInstanceOf<ConstantReference>().name.text } shouldContainExactly
            listOf("email", "createdAt")
    }

    @Test
    fun `members remember the blank line the author left above them`() {
        val user = document.models.single { it.name.text == "User" }
        user.members.first { it is BlockAttributeDeclaration }.blankLineBefore shouldBe true
        user.fields.single { it.name.text == "email" }.blankLineBefore shouldBe false
    }

    @Test
    fun `trailing comments stay attached to the line that carried them`() {
        val datasource = document.datasources.single()
        datasource.entries[0].trailingComment.shouldNotBeNull().text shouldContain "postgresql | mysql"
        datasource.entries[1].trailingComment shouldBe null
    }

    @Test
    fun `spans point at the exact source text`() {
        val user = document.models.single { it.name.text == "User" }
        val email = user.fields.single { it.name.text == "email" }
        val text = Fixtures.read("reference.volan")
        text.substring(email.span.start, email.span.end) shouldBe "email     String   @unique"
        text.substring(email.name.span.start, email.name.span.end) shouldBe "email"
    }

    @Test
    fun `keywords are not reserved and may be used as names`() {
        val parsed = SchemaParser.parse("schema.volan", "model Model {\n  model String\n  enum Int\n}\n")
        parsed.render() shouldBe ""
        parsed.document.models.single().fields.map { it.name.text } shouldContainExactly listOf("model", "enum")
    }

    @Test
    fun `layout does not matter`() {
        val dense = SchemaParser.parse("schema.volan", "model User{id Int @id email String @unique}")
        dense.render() shouldBe ""
        dense.document.models.single().fields.map { it.name.text } shouldContainExactly listOf("id", "email")
    }

    @Test
    fun `documentOrThrow returns the document for a valid schema`() {
        SchemaParser.parseOrThrow(Fixtures.source("reference.volan")).models.size shouldBe 2
    }
}
