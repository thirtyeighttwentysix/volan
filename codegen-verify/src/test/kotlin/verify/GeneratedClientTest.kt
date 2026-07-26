package verify

import com.example.blog.Post
import com.example.blog.Role
import com.example.blog.User
import com.example.blog.UserRowMapper
import com.example.blog.UserTable
import com.example.blog.VolanClient
import io.github.thirtyeighttwentysix.volan.runtime.ComparisonOperator
import io.github.thirtyeighttwentysix.volan.runtime.Filter
import io.github.thirtyeighttwentysix.volan.runtime.RelationQuantifier
import io.github.thirtyeighttwentysix.volan.runtime.RelationSlot
import io.github.thirtyeighttwentysix.volan.runtime.SortDirection
import io.github.thirtyeighttwentysix.volan.runtime.VolanFieldNotSelectedException
import io.github.thirtyeighttwentysix.volan.runtime.VolanRelationNotLoadedException
import io.github.thirtyeighttwentysix.volan.runtime.NestedWrite
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Exercises the generated client the way an application would.
 *
 * That this file compiles at all is the point: it means the generator produced Kotlin that a user's
 * project can build against. What the tests then check is that the client describes the query the
 * caller wrote and maps the answer back.
 */
class GeneratedClientTest {
    private val now = Instant.parse("2026-07-26T10:15:30Z")

    private val aliceRow = mapOf(
        "id" to 1,
        "email" to "alice@acme.com",
        "name" to "Alice",
        "role" to "administrator",
        "created_at" to now,
        "updated_at" to now,
        "managerId" to null,
    )

    @Test
    fun `a read describes the query the caller wrote`() {
        val executor = FakeExecutor()
        VolanClient(executor).user.findMany {
            where {
                email endsWith "@acme.com"
                role eq Role.ADMIN
                or {
                    name.isNull()
                    createdAt gt now
                }
            }
            orderBy { createdAt.desc() }
            take = 20
            skip = 5
        }

        val spec = executor.query
        spec.model shouldBe "User"
        spec.pagination.take shouldBe 20
        spec.pagination.skip shouldBe 5
        spec.orderBy.single().column shouldBe "created_at"
        spec.orderBy.single().direction shouldBe SortDirection.DESCENDING
        spec.filter shouldBe Filter.And(
            listOf(
                Filter.Compare("email", ComparisonOperator.ENDS_WITH, "@acme.com"),
                Filter.Compare("role", ComparisonOperator.EQUAL, "administrator"),
                Filter.Or(
                    listOf(
                        Filter.IsNull("name"),
                        Filter.Compare("created_at", ComparisonOperator.GREATER_THAN, now),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `mapped names are used everywhere the database is addressed`() {
        val executor = FakeExecutor()
        VolanClient(executor).user.findMany { where { createdAt gt now } }
        executor.query.filter.shouldBeInstanceOf<Filter.Compare>().column shouldBe "created_at"
        UserTable.METADATA.table shouldBe "users"
        UserTable.CREATED_AT shouldBe "created_at"
    }

    @Test
    fun `enum values travel as the database stores them`() {
        val executor = FakeExecutor()
        VolanClient(executor).user.findMany { where { role eq Role.ADMIN } }
        executor.query.filter.shouldBeInstanceOf<Filter.Compare>().value shouldBe "administrator"
        Role.ADMIN.databaseValue shouldBe "administrator"
        Role.fromDatabaseValue("administrator") shouldBe Role.ADMIN
    }

    @Test
    fun `a relation filter names the relation and the quantifier`() {
        val executor = FakeExecutor()
        VolanClient(executor).user.findMany { where { posts { some { title contains "Kotlin" } } } }
        val filter = executor.query.filter.shouldBeInstanceOf<Filter.Related>()
        filter.relation shouldBe "posts"
        filter.quantifier shouldBe RelationQuantifier.SOME
        filter.filter shouldBe Filter.Compare("title", ComparisonOperator.CONTAINS, "Kotlin")
    }

    @Test
    fun `include asks for a relation with its own query`() {
        val executor = FakeExecutor()
        VolanClient(executor).user.findMany {
            include {
                posts {
                    where { title contains "Kotlin" }
                    orderBy { views.desc() }
                    take = 5
                }
            }
        }
        val request = executor.query.includes.single()
        request.relation shouldBe "posts"
        request.spec.model shouldBe "Post"
        request.spec.pagination.take shouldBe 5
        request.spec.filter shouldBe Filter.Compare("title", ComparisonOperator.CONTAINS, "Kotlin")
    }

    @Test
    fun `nested includes are described to any depth`() {
        val executor = FakeExecutor()
        VolanClient(executor).user.findMany { include { posts { include { comments { } } } } }
        val posts = executor.query.includes.single()
        posts.spec.includes.single().relation shouldBe "comments"
    }

    @Test
    fun `rows are mapped without reflection, honouring mapped column names`() {
        val user = UserRowMapper.map(MapRow(aliceRow))
        user.id shouldBe 1
        user.email shouldBe "alice@acme.com"
        user.name shouldBe "Alice"
        user.role shouldBe Role.ADMIN
        user.createdAt shouldBe now
        user.managerId.shouldBeNull()
    }

    @Test
    fun `a relation nobody asked for says which query to change`() {
        val user = UserRowMapper.map(MapRow(aliceRow))
        user.isPostsLoaded shouldBe false
        user.postsIfLoaded.shouldBeNull()
        val thrown = runCatching { user.posts }.exceptionOrNull()
        (thrown is VolanRelationNotLoadedException) shouldBe true
        thrown?.message.orEmpty() shouldContain "include { posts { } }"
    }

    @Test
    fun `a loaded relation reads back as the rows that were fetched`() {
        val post = Post.builder().id(7).title("Hello").views(0).draft(false).authorId(1).build()
        val user = User(
            id = 1,
            email = "alice@acme.com",
            name = null,
            role = Role.USER,
            createdAt = now,
            updatedAt = now,
            managerId = null,
            postsSlot = RelationSlot.loaded(listOf(post)),
        )
        user.isPostsLoaded shouldBe true
        user.posts.single().title shouldBe "Hello"
    }

    @Test
    fun `a projection refuses the fields the select left out`() {
        val executor = FakeExecutor(listOf(aliceRow))
        val row = VolanClient(executor).user.projectMany {
            select {
                email
                name
            }
        }.single()

        row.email shouldBe "alice@acme.com"
        row.isEmailSelected shouldBe true
        row.isIdSelected shouldBe false
        executor.query.columns shouldContainExactly listOf("email", "name")

        val thrown = runCatching { row.id }.exceptionOrNull()
        (thrown is VolanFieldNotSelectedException) shouldBe true
        thrown?.message.orEmpty() shouldContain "select { id }"
    }

    @Test
    fun `a write carries only the fields the block set`() {
        val executor = FakeExecutor(listOf(aliceRow))
        VolanClient(executor).user.create {
            email = "alice@acme.com"
            name = "Alice"
        }
        val spec = executor.creates.single()
        spec.model shouldBe "User"
        spec.values.keys.toList() shouldContainExactly listOf("email", "name")
    }

    @Test
    fun `a write carries only what the block set, leaving the rest to the database and to nested writes`() {
        val executor = FakeExecutor(listOf(aliceRow))
        VolanClient(executor).user.create { name = "Alice" }
        // Whether `email` had to be there is decided by the runtime, once nested writes have supplied
        // whatever they supply; the payload's job is only to report what was written in the block.
        executor.creates.single().values.keys.toList() shouldContainExactly listOf("name")
    }

    @Test
    fun `a nested write travels with the row it belongs to`() {
        val executor = FakeExecutor(listOf(aliceRow))
        VolanClient(executor).user.create {
            email = "alice@acme.com"
            posts.create { title = "Hello" }
        }
        val nested = executor.creates.single().nested.single()
        nested.relation shouldBe "posts"
        nested.shouldBeInstanceOf<NestedWrite.CreateRows>().rows.single().values["title"] shouldBe "Hello"
    }

    @Test
    fun `an update writes only what changed, and knows null from unset`() {
        val executor = FakeExecutor(listOf(aliceRow))
        VolanClient(executor).user.update {
            where { id eq 1 }
            data { name = null }
        }
        val spec = executor.updates.single()
        spec.filter shouldBe Filter.Compare("id", ComparisonOperator.EQUAL, 1)
        spec.values shouldBe mapOf("name" to null)
    }

    @Test
    fun `updatedAt is never written by hand`() {
        val executor = FakeExecutor(listOf(aliceRow))
        VolanClient(executor).user.update {
            where { id eq 1 }
            data { name = "Bob" }
        }
        executor.updates.single().values.keys.toList() shouldContainExactly listOf("name")
    }

    @Test
    fun `upsert carries the row to look for and both payloads`() {
        val executor = FakeExecutor(listOf(aliceRow))
        VolanClient(executor).user.upsert {
            where { email eq "alice@acme.com" }
            create {
                email = "alice@acme.com"
                name = "Alice"
            }
            update { name = "Alice II" }
        }
        val spec = executor.upserts.single()
        spec.create.keys.toList() shouldContainExactly listOf("email", "name")
        spec.update shouldBe mapOf("name" to "Alice II")
    }

    @Test
    fun `deleteMany describes which rows to remove`() {
        val executor = FakeExecutor(listOf(aliceRow))
        VolanClient(executor).post.deleteMany { where { authorId eq 1 } }
        executor.deletes.single().filter shouldBe Filter.Compare("authorId", ComparisonOperator.EQUAL, 1)
    }

    @Test
    fun `createMany batches the rows it was given`() {
        val executor = FakeExecutor()
        val written = VolanClient(executor).tag.createMany {
            row { name = "kotlin" }
            row { name = "jvm" }
        }
        written shouldBe 2
        executor.creates.map { it.values["name"] } shouldContainExactly listOf("kotlin", "jvm")
    }

    @Test
    fun `a cursor pages by key rather than by offset`() {
        val executor = FakeExecutor()
        VolanClient(executor).user.findMany { cursor(id = 42) }
        executor.query.pagination.cursor shouldBe mapOf("id" to 42)
        executor.query.pagination.skipCursorRow shouldBe true
    }

    @Test
    fun `a composite primary key becomes a composite cursor`() {
        val executor = FakeExecutor()
        VolanClient(executor).comment.findMany { cursor(postId = 1L, authorId = 2) }
        executor.query.pagination.cursor shouldBe mapOf("postId" to 1L, "authorId" to 2)
    }

    @Test
    fun `entities compare by their data, not by what was loaded alongside`() {
        val plain = UserRowMapper.map(MapRow(aliceRow))
        val withPosts = User(
            id = 1,
            email = "alice@acme.com",
            name = "Alice",
            role = Role.ADMIN,
            createdAt = now,
            updatedAt = now,
            managerId = null,
            postsSlot = RelationSlot.loaded(emptyList()),
        )
        (plain == withPosts) shouldBe true
        plain.hashCode() shouldBe withPosts.hashCode()
        plain.toString() shouldContain "email=alice@acme.com"
    }

    @Test
    fun `the ignored model is not part of the client at all`() {
        val names = VolanClient::class.java.methods.map { it.name }
        names.none { it.contains("Legacy", ignoreCase = true) } shouldBe true
    }
}
