package io.github.thirtyeighttwentysix.volan.ir

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SchemaLoaderTest {
    private val result = SchemaLoader.load(Fixtures.source("blog.volan"))
    private val schema = result.schemaOrThrow()

    @Test
    fun `the reference schema analyses without diagnostics`() {
        result.render() shouldBe ""
    }

    @Test
    fun `the ir matches its snapshot`() {
        IrPrinter.print(schema) shouldBe Fixtures.read("blog.ir.txt")
    }

    @Test
    fun `the datasource is resolved, with the url left in the environment`() {
        schema.datasource.provider shouldBe Provider.POSTGRESQL
        schema.datasource.url shouldBe ConnectionUrl.Environment("DATABASE_URL")
    }

    @Test
    fun `generator options fall back to their defaults`() {
        val minimal = SchemaLoader.load(
            "schema.volan",
            """
            datasource db {
              provider = "sqlite"
              url      = env("DATABASE_URL")
            }

            generator client {
              provider = "volan-kotlin"
              package  = "com.example"
            }

            model User {
              id Int @id
            }
            """.trimIndent(),
        ).schemaOrThrow()
        val generator = minimal.generators.single()
        generator.outputDirectory shouldBe "build/generated/volan"
        generator.javaFriendly shouldBe false
    }

    @Test
    fun `map moves names into the database without changing them in the schema`() {
        val user = schema.model("User").shouldNotBeNull()
        user.name shouldBe "User"
        user.dbName shouldBe "users"
        user.field("createdAt").shouldNotBeNull().dbName shouldBe "created_at"
        user.field("email").shouldNotBeNull().dbName shouldBe "email"
        schema.enumType("Role").shouldNotBeNull().dbName shouldBe "user_role"
        schema.enumType("Role").shouldNotBeNull().value("ADMIN").shouldNotBeNull().dbName shouldBe "administrator"
    }

    @Test
    fun `scalar and enum fields keep their type, arity and attributes`() {
        val user = schema.model("User").shouldNotBeNull()
        user.field("id").shouldNotBeNull().type shouldBe FieldType.Scalar(ScalarType.INT)
        user.field("id").shouldNotBeNull().default shouldBe DefaultValue.AutoIncrement
        user.field("name").shouldNotBeNull().cardinality shouldBe Cardinality.OPTIONAL
        user.field("role").shouldNotBeNull().type shouldBe FieldType.EnumRef("Role")
        user.field("role").shouldNotBeNull().default shouldBe DefaultValue.EnumValueRef("Role", "USER")
        user.field("updatedAt").shouldNotBeNull().isUpdatedAt shouldBe true
    }

    @Test
    fun `native types are recorded as written`() {
        val native = schema.model("User").shouldNotBeNull().field("email").shouldNotBeNull().nativeType
        native.shouldNotBeNull().name shouldBe "VarChar"
        native.arguments shouldContainExactly listOf("320")
    }

    @Test
    fun `documentation comments are carried into the ir`() {
        schema.model("User").shouldNotBeNull().documentation shouldBe "Someone who can sign in."
        schema.enumType("Role").shouldNotBeNull().documentation shouldBe "What a person is allowed to do."
        val manager = schema.model("User").shouldNotBeNull().relationField("manager").shouldNotBeNull()
        manager.documentation shouldBe "Who this person reports to, if anyone."
    }

    @Test
    fun `a composite primary key is resolved in the order it was written`() {
        val comment = schema.model("Comment").shouldNotBeNull()
        comment.primaryKey.shouldNotBeNull().fields shouldContainExactly listOf("postId", "authorId")
        comment.primaryKeyFields.map { it.type } shouldContainExactly
            listOf(FieldType.Scalar(ScalarType.LONG), FieldType.Scalar(ScalarType.INT))
    }

    @Test
    fun `indexes keep their kind`() {
        val post = schema.model("Post").shouldNotBeNull()
        post.indexes.map { it.kind } shouldContainExactly listOf(IndexKind.BTREE, IndexKind.FULLTEXT)
        post.indexes[1].fields shouldContainExactly listOf("title", "body")
    }

    @Test
    fun `one-to-many relations put the foreign key on the side that holds one row`() {
        val relation = schema.relations.single { it.name == "PostToUser" }
        relation.kind shouldBe RelationKind.ONE_TO_MANY
        relation.from shouldBe RelationEnd("Post", "author", Cardinality.REQUIRED)
        relation.to shouldBe RelationEnd("User", "posts", Cardinality.LIST)
        relation.foreignKeyFields shouldContainExactly listOf("authorId")
        relation.referencedFields shouldContainExactly listOf("id")
        relation.onDelete shouldBe ReferentialAction.CASCADE
    }

    @Test
    fun `one-to-one relations are recognised by both sides holding one row`() {
        val relation = schema.relations.single { it.name == "ProfileToUser" }
        relation.kind shouldBe RelationKind.ONE_TO_ONE
        relation.from.model shouldBe "Profile"
        relation.joinTable shouldBe null
    }

    @Test
    fun `many-to-many relations get a join table and no foreign key`() {
        val relation = schema.relations.single { it.name == "PostTags" }
        relation.kind shouldBe RelationKind.MANY_TO_MANY
        relation.joinTable shouldBe "_PostTags"
        relation.foreignKeyFields.shouldBeEmpty()
        relation.referencedFields.shouldBeEmpty()
    }

    @Test
    fun `a self-relation pairs two fields of the same model`() {
        val relation = schema.relations.single { it.name == "Reports" }
        relation.isSelfRelation shouldBe true
        relation.from shouldBe RelationEnd("User", "manager", Cardinality.OPTIONAL)
        relation.to shouldBe RelationEnd("User", "reports", Cardinality.LIST)
        relation.onDelete shouldBe ReferentialAction.SET_NULL
    }

    @Test
    fun `relations can be looked up by the model they touch`() {
        schema.relationsOf("Tag").map { it.name } shouldContainExactly listOf("PostTags")
        schema.relationsOf("User").map { it.name } shouldContainExactly
            listOf("CommentToUser", "PostToUser", "ProfileToUser", "Reports")
    }

    @Test
    fun `loadOrThrow reports every diagnostic in its message`() {
        val thrown = runCatching {
            SchemaLoader.load("schema.volan", "model User {\n  id Int @id\n  tag Tag\n}\n").schemaOrThrow()
        }.exceptionOrNull()
        thrown?.message.orEmpty() shouldContain "unknown type `Tag`"
    }
}
