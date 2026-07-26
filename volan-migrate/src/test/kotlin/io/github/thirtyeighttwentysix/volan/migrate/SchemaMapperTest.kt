package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefault
import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyAction
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SchemaMapperTest {
    private val database = SchemaMapper.map(Fixtures.blog())

    @Test
    fun `every model becomes a table under its mapped name`() {
        database.tables.map { it.name } shouldContainExactly
            listOf("Post", "Profile", "Tag", "_PostTags", "post_comments", "users")
    }

    @Test
    fun `a mapped column keeps the name the schema gave the database`() {
        val users = database.table("users").shouldNotBeNull()
        users.columns.map { it.name } shouldContainExactly
            listOf("id", "email", "name", "role", "created_at", "updated_at", "managerId")
    }

    @Test
    fun `types are carried as SQL's own vocabulary, not as the schema language's`() {
        val post = database.table("Post").shouldNotBeNull()
        post.column("id").shouldNotBeNull().type shouldBe ColumnType.Scalar(SqlType.BIGINT)
        post.column("views").shouldNotBeNull().type shouldBe ColumnType.Scalar(SqlType.INTEGER)
        post.column("draft").shouldNotBeNull().type shouldBe ColumnType.Scalar(SqlType.BOOLEAN)
    }

    @Test
    fun `a native type is passed through exactly as it was written`() {
        val users = database.table("users").shouldNotBeNull()
        users.column("email").shouldNotBeNull().type shouldBe ColumnType.Native("VarChar", listOf("320"))
    }

    @Test
    fun `an enum field takes the enum type, under the name the schema mapped it to`() {
        val role = database.table("users").shouldNotBeNull().column("role").shouldNotBeNull()
        role.type shouldBe ColumnType.Enumeration("user_role")
        role.default shouldBe ColumnDefault.Text("USER")
        database.enumType("user_role").shouldNotBeNull().values shouldContainExactly listOf("USER", "administrator")
    }

    @Test
    fun `autoincrement is a property of the column rather than a default`() {
        val id = database.table("users").shouldNotBeNull().column("id").shouldNotBeNull()
        id.autoIncrement shouldBe true
        id.default.shouldBeNull()
    }

    @Test
    fun `updatedAt is written by volan, so the column carries no default`() {
        database.table("users").shouldNotBeNull().column("updated_at").shouldNotBeNull().default.shouldBeNull()
    }

    @Test
    fun `now becomes the database's own idea of the current moment`() {
        database.table("users").shouldNotBeNull().column("created_at").shouldNotBeNull().default shouldBe
            ColumnDefault.CurrentTimestamp
    }

    @Test
    fun `keys and constraints are named by rule, so a diff can recognise them again`() {
        val users = database.table("users").shouldNotBeNull()
        users.primaryKey.shouldNotBeNull().name shouldBe "users_pkey"
        users.uniques.map { it.name } shouldContainExactly listOf("users_email_key")
        users.indexes.map { it.name } shouldContainExactly listOf("users_email_created_at_idx")
    }

    @Test
    fun `a composite key becomes one key over the columns it names`() {
        val comments = database.table("post_comments").shouldNotBeNull()
        comments.primaryKey.shouldNotBeNull().columns shouldContainExactly listOf("postId", "authorId")
    }

    @Test
    fun `a foreign key is written on the side that holds it, with the action the schema asked for`() {
        val posts = database.table("Post").shouldNotBeNull()
        val key = posts.foreignKeys.single()
        key.name shouldBe "Post_authorId_fkey"
        key.columns shouldContainExactly listOf("authorId")
        key.targetTable shouldBe "users"
        key.targetColumns shouldContainExactly listOf("id")
        key.onDelete shouldBe ForeignKeyAction.CASCADE
    }

    @Test
    fun `a relation that says onDelete gets what it asked for`() {
        database.table("users").shouldNotBeNull().foreignKeys.single().onDelete shouldBe ForeignKeyAction.SET_NULL
    }

    @Test
    fun `a relation with no onDelete refuses the delete when it is required and forgets when it is not`() {
        val quiet = SchemaMapper.map(Fixtures.schema(QUIET_SCHEMA))
        quiet.table("Post").shouldNotBeNull().foreignKeys.single().onDelete shouldBe ForeignKeyAction.RESTRICT
        quiet.table("Note").shouldNotBeNull().foreignKeys.single().onDelete shouldBe ForeignKeyAction.SET_NULL
    }

    @Test
    fun `a many-to-many relation becomes a table of its own, keyed to both sides`() {
        val join = database.table("_PostTags").shouldNotBeNull()
        join.columns.map { it.name } shouldContainExactly listOf("A", "B")
        join.column("A").shouldNotBeNull().type shouldBe ColumnType.Scalar(SqlType.BIGINT)
        join.column("B").shouldNotBeNull().type shouldBe ColumnType.Scalar(SqlType.INTEGER)
        join.uniques.single().columns shouldContainExactly listOf("A", "B")
        join.indexes.single().columns shouldContainExactly listOf("B")
        join.foreignKeys.map { it.targetTable } shouldContainExactly listOf("Post", "Tag")
        join.foreignKeys.forEach { it.onDelete shouldBe ForeignKeyAction.CASCADE }
    }

    @Test
    fun `a cuid default says so rather than producing a column no database can fill`() {
        val failure = shouldThrow<VolanMigrationException> {
            SchemaMapper.map(Fixtures.schema(CUID_SCHEMA))
        }
        failure.message.shouldContain("no database can produce")
        failure.message.shouldContain("@default(uuid())")
    }

    private companion object {
        private val QUIET_SCHEMA = """
            datasource db {
              provider = "postgresql"
              url      = env("DATABASE_URL")
            }

            model Author {
              id    Int    @id @default(autoincrement())
              posts Post[]
              notes Note[]
            }

            model Post {
              id       Int    @id @default(autoincrement())
              author   Author @relation(fields: [authorId], references: [id])
              authorId Int
            }

            model Note {
              id       Int     @id @default(autoincrement())
              author   Author? @relation(fields: [authorId], references: [id])
              authorId Int?
            }
        """.trimIndent()

        private val CUID_SCHEMA = """
            datasource db {
              provider = "postgresql"
              url      = env("DATABASE_URL")
            }

            model Note {
              id   String @id @default(cuid())
              body String
            }
        """.trimIndent()
    }
}
