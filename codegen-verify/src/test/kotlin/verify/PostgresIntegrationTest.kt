package verify

import com.example.blog.Role
import com.example.blog.VolanClient
import io.github.thirtyeighttwentysix.volan.dialect.postgres.PostgresDialect
import io.github.thirtyeighttwentysix.volan.runtime.Isolation
import io.github.thirtyeighttwentysix.volan.runtime.Volan
import io.github.thirtyeighttwentysix.volan.runtime.VolanAggregateNotAskedException
import io.github.thirtyeighttwentysix.volan.runtime.VolanNotFoundException
import io.github.thirtyeighttwentysix.volan.runtime.VolanRelationNotLoadedException
import io.github.thirtyeighttwentysix.volan.runtime.VolanUniqueConstraintException
import io.github.thirtyeighttwentysix.volan.runtime.VolanValidationException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Instant
import javax.sql.DataSource

/**
 * Whether there is a Docker daemon to run PostgreSQL in.
 *
 * The CI matrix includes macOS and Windows runners, which have none; those jobs still build and run
 * everything else, and the Linux job is where this suite actually runs.
 */
object Docker {
    @JvmStatic
    fun isAvailable(): Boolean = DockerClientFactory.instance().isDockerAvailable
}

/**
 * The whole stack against a real PostgreSQL: the generated client, the planner, the dialect and JDBC.
 *
 * Everything above this has been checked against descriptions and doubles. This is where the
 * descriptions meet a database that has its own opinions about types, constraints and isolation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("verify.Docker#isAvailable")
class PostgresIntegrationTest {
    private val container = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).apply { start() }

    private val client = VolanClient.builder()
        .url(container.jdbcUrl)
        .username(container.username)
        .password(container.password)
        .maxPoolSize(4)
        .build()

    @AfterAll
    fun stop() {
        client.close()
        container.stop()
    }

    @BeforeEach
    fun resetSchema() {
        val ddl = requireNotNull(javaClass.getResourceAsStream("/blog-schema.sql")) { "missing blog-schema.sql" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("drop schema public cascade; create schema public;")
                statement.execute(ddl)
            }
        }
    }

    private fun alice() = client.user.create {
        email = "alice@acme.com"
        name = "Alice"
        role = Role.ADMIN
    }

    @Test
    fun `a create writes the row and reads it back`() {
        val user = alice()
        user.id shouldBe 1
        user.email shouldBe "alice@acme.com"
        user.name shouldBe "Alice"
        user.role shouldBe Role.ADMIN
    }

    @Test
    fun `a column with a database default is left to the database`() {
        val user = client.user.create { email = "bob@acme.com" }
        user.role shouldBe Role.USER
        user.name.shouldBeNull()
    }

    @Test
    fun `updatedAt is written by volan on create and on update`() {
        val created = alice()
        val updated = client.user.update {
            where { id eq created.id }
            data { name = "Alice II" }
        }
        updated.name shouldBe "Alice II"
        (updated.updatedAt >= created.updatedAt) shouldBe true
    }

    @Test
    fun `an enum travels as the value the schema maps it to`() {
        alice()
        readSingleValue("select role from users where id = 1") shouldBe "administrator"
        client.user.findFirst { where { role eq Role.ADMIN } }.shouldNotBeNull().role shouldBe Role.ADMIN
    }

    @Test
    fun `filters reach the database as conditions, not as text`() {
        alice()
        client.user.create { email = "bob@corp.example" }
        client.user.create {
            email = "carol@acme.com"
            name = "Carol"
        }

        client.user.findMany { where { email endsWith "@acme.com" } }.map { it.email } shouldContainExactly
            listOf("alice@acme.com", "carol@acme.com")
        client.user.findMany { where { name.isNull() } }.map { it.email } shouldContainExactly listOf("bob@corp.example")
        client.user.findMany { where { id oneOf listOf(1, 3) } }.map { it.id } shouldContainExactly listOf(1, 3)
        client.user.findMany { where { id.between(2, 3) } }.map { it.id } shouldContainExactly listOf(2, 3)
        client.user.count { where { email contains "acme" } } shouldBe 2
        client.user.exists { where { email eq "nobody@acme.com" } } shouldBe false
    }

    @Test
    fun `a search for a wildcard finds the wildcard, not everything`() {
        client.user.create { email = "100%@acme.com" }
        client.user.create { email = "1000@acme.com" }
        client.user.findMany { where { email startsWith "100%" } }.map { it.email } shouldContainExactly
            listOf("100%@acme.com")
    }

    @Test
    fun `case-insensitive matching is asked of the database, not emulated`() {
        alice()
        client.user.findMany { where { email.ignoringCase() eq "ALICE@ACME.COM" } }.size shouldBe 1
        client.user.findMany { where { email.ignoringCase() contains "ACME" } }.size shouldBe 1
    }

    @Test
    fun `ordering and paging are applied by the database`() {
        repeat(5) { index -> client.user.create { email = "user$index@acme.com" } }
        client.user.findMany {
            orderBy { email.desc() }
            take = 2
            skip = 1
        }.map { it.email } shouldContainExactly listOf("user3@acme.com", "user2@acme.com")
    }

    @Test
    fun `a cursor resumes after a row rather than counting from the start`() {
        repeat(4) { index -> client.user.create { email = "user$index@acme.com" } }
        client.user.findMany { cursor(id = 2) }.map { it.id } shouldContainExactly listOf(3, 4)
        client.user.findMany { cursor(id = 2, inclusive = true) }.map { it.id } shouldContainExactly listOf(2, 3, 4)
    }

    @Test
    fun `a filter on a relation becomes a subquery the database answers`() {
        val user = alice()
        val other = client.user.create { email = "bob@acme.com" }
        client.post.create {
            title = "Kotlin on the JVM"
            authorId = user.id
        }
        client.post.create {
            title = "Something else"
            authorId = other.id
        }

        client.user.findMany { where { posts { some { title contains "Kotlin" } } } }.map { it.id } shouldContainExactly
            listOf(user.id)
        client.user.findMany { where { posts { none { title contains "Kotlin" } } } }.map { it.id } shouldContainExactly
            listOf(other.id)
        client.post.findMany { where { author { matches { email eq "alice@acme.com" } } } }.size shouldBe 1
    }

    @Test
    fun `a partial select reads only the columns it asked for`() {
        alice()
        val row = client.user.projectMany {
            select {
                id
                email
            }
        }.single()
        row.email shouldBe "alice@acme.com"
        row.isNameSelected shouldBe false
    }

    @Test
    fun `createMany writes every row in one statement`() {
        client.tag.createMany {
            row { name = "kotlin" }
            row { name = "jvm" }
        } shouldBe 2
        client.tag.findMany { orderBy { name.asc() } }.map { it.name } shouldContainExactly listOf("jvm", "kotlin")
    }

    @Test
    fun `updateMany changes every matching row and says how many`() {
        client.user.create { email = "a@acme.com" }
        client.user.create { email = "b@acme.com" }
        client.user.updateMany {
            where { email endsWith "@acme.com" }
            data { role = Role.ADMIN }
        } shouldBe 2
        client.user.findMany { where { role eq Role.ADMIN } }.size shouldBe 2
    }

    @Test
    fun `upsert inserts when there is nothing and updates when there is`() {
        val inserted = client.user.upsert {
            where { email eq "alice@acme.com" }
            create {
                email = "alice@acme.com"
                name = "Alice"
            }
            update { name = "unused" }
        }
        inserted.name shouldBe "Alice"

        val updated = client.user.upsert {
            where { email eq "alice@acme.com" }
            create {
                email = "alice@acme.com"
                name = "unused"
            }
            update { name = "Alice II" }
        }
        updated.id shouldBe inserted.id
        updated.name shouldBe "Alice II"
        client.user.count() shouldBe 1
    }

    @Test
    fun `delete removes the row and hands back what was removed`() {
        val user = alice()
        client.post.create {
            title = "Hello"
            authorId = user.id
        }
        client.post.deleteMany { where { authorId eq user.id } } shouldBe 1
        val removed = client.user.delete { where { id eq user.id } }
        removed.email shouldBe "alice@acme.com"
        client.user.count() shouldBe 0
    }

    @Test
    fun `deleting nothing says so rather than pretending`() {
        val thrown = runCatching { client.user.delete { where { id eq 999 } } }.exceptionOrNull()
        (thrown is VolanNotFoundException) shouldBe true
        thrown?.message.orEmpty() shouldContain "nothing to remove"
    }

    @Test
    fun `a unique violation arrives as a unique violation`() {
        alice()
        val thrown = runCatching { alice() }.exceptionOrNull()
        (thrown is VolanUniqueConstraintException) shouldBe true
        (thrown as VolanUniqueConstraintException).constraint shouldBe "users_email_key"
        thrown.message.orEmpty() shouldContain "duplicate"
    }

    @Test
    fun `a transaction commits everything or nothing`() {
        client.transaction { tx ->
            tx.user.create { email = "in-transaction@acme.com" }
        }
        client.user.count() shouldBe 1

        runCatching {
            client.transaction { tx ->
                tx.user.create { email = "rolled-back@acme.com" }
                error("something went wrong after the write")
            }
        }
        client.user.count() shouldBe 1
    }

    @Test
    fun `a nested transaction rolls back to its savepoint without taking the outer one down`() {
        client.transaction { outer ->
            outer.user.create { email = "kept@acme.com" }
            runCatching {
                outer.transaction { inner ->
                    inner.user.create { email = "discarded@acme.com" }
                    error("the inner block fails")
                }
            }
            outer.user.create { email = "also-kept@acme.com" }
        }
        client.user.findMany { orderBy { email.asc() } }.map { it.email } shouldContainExactly
            listOf("also-kept@acme.com", "kept@acme.com")
    }

    @Test
    fun `an isolation level is asked of the database, not just recorded`() {
        val level = client.transaction(isolation = Isolation.SERIALIZABLE) { tx ->
            tx.user.create { email = "serial@acme.com" }
            tx.rawQuery("show transaction_isolation", emptyList()) { it.getString("transaction_isolation") }.single()
        }
        level shouldBe "serializable"
        client.user.count() shouldBe 1
    }

    @Test
    fun `raw sql still binds its values`() {
        alice()
        val emails = client.rawQuery(
            "select email from users where email = ?",
            listOf("alice@acme.com"),
        ) { it.getString("email") }
        emails shouldContainExactly listOf("alice@acme.com")
        client.rawExecute("update users set name = ? where id = ?", listOf("Renamed", 1)) shouldBe 1
        client.user.findUnique { where { id eq 1 } }.shouldNotBeNull().name shouldBe "Renamed"
    }

    @Test
    fun `include loads a to-many relation and hands each row its own share`() {
        val user = alice()
        val other = client.user.create { email = "bob@acme.com" }
        client.post.create {
            title = "First"
            authorId = user.id
        }
        client.post.create {
            title = "Second"
            authorId = user.id
        }
        client.post.create {
            title = "Bob's"
            authorId = other.id
        }

        val users = client.user.findMany {
            orderBy { id.asc() }
            include { posts { orderBy { title.asc() } } }
        }
        users.first { it.id == user.id }.posts.map { it.title } shouldContainExactly listOf("First", "Second")
        users.first { it.id == other.id }.posts.map { it.title } shouldContainExactly listOf("Bob's")
    }

    @Test
    fun `include loads a to-one relation, and absent is different from not loaded`() {
        val user = alice()
        client.profile.create {
            bio = "Writes things"
            userId = user.id
        }
        val other = client.user.create { email = "bob@acme.com" }

        val users = client.user.findMany { include { profile { } } }
        users.first { it.id == user.id }.profile.shouldNotBeNull().bio shouldBe "Writes things"
        val bob = users.first { it.id == other.id }
        bob.isProfileLoaded shouldBe true
        bob.profile.shouldBeNull()
    }

    @Test
    fun `a relation that was not asked for still refuses to be read`() {
        alice()
        val user = client.user.findMany { include { posts { } } }.single()
        user.isPostsLoaded shouldBe true
        val thrown = runCatching { user.profile }.exceptionOrNull()
        (thrown is VolanRelationNotLoadedException) shouldBe true
    }

    @Test
    fun `an include can be filtered and paged on its own side`() {
        val user = alice()
        listOf("Kotlin one", "Kotlin two", "Java one").forEach { subject ->
            client.post.create {
                title = subject
                authorId = user.id
            }
        }
        val loaded = client.user.findMany {
            include {
                posts {
                    where { title startsWith "Kotlin" }
                    orderBy { title.desc() }
                    take = 1
                }
            }
        }.single()
        loaded.posts.map { it.title } shouldContainExactly listOf("Kotlin two")
    }

    @Test
    fun `includes nest, and each level costs one statement rather than one per row`() {
        val counter = java.util.concurrent.atomic.AtomicInteger()
        countingClient(counter).use { counted ->
            val user = alice()
            repeat(3) { index ->
                val post = client.post.create {
                    title = "Post $index"
                    authorId = user.id
                }
                client.comment.create {
                    postId = post.id
                    authorId = user.id
                    body = "Comment on $index"
                }
            }

            counter.set(0)
            val users = counted.user.findMany { include { posts { include { comments { } } } } }
            users.single().posts.size shouldBe 3
            users.single().posts.first().comments.size shouldBe 1

            // One statement for the users, one for every post, one for every comment: three in total,
            // whatever the number of rows at each level.
            counter.get() shouldBe 3
        }
    }

    @Test
    fun `a create writes the rows it brings with it, in one transaction`() {
        val user = client.user.create {
            email = "alice@acme.com"
            name = "Alice"
            posts.create { title = "Hello" }
            posts.create { title = "Second" }
            profile.create { bio = "Writes things" }
        }

        client.post.findMany { where { authorId eq user.id } }.map { it.title }.sorted() shouldContainExactly
            listOf("Hello", "Second")
        client.profile.findFirst { where { userId eq user.id } }.shouldNotBeNull().bio shouldBe "Writes things"
    }

    @Test
    fun `a nested write reaches through more than one level`() {
        val user = client.user.create {
            email = "alice@acme.com"
            posts.create {
                title = "Hello"
                tags.connectOrCreate { name = "kotlin" }
            }
        }
        val post = client.post.findFirst {
            where { authorId eq user.id }
            include { tags { } }
        }.shouldNotBeNull()
        post.title shouldBe "Hello"
        post.tags.single().name shouldBe "kotlin"
    }

    @Test
    fun `a required field that was never set fails before a statement is built`() {
        val thrown = runCatching { client.user.create { name = "Alice" } }.exceptionOrNull()
        (thrown is VolanValidationException) shouldBe true
        thrown?.message.orEmpty() shouldContain "`User.email` is required"
        client.user.count() shouldBe 0
    }

    @Test
    fun `connect attaches an existing row, on the side that holds the key`() {
        val existing = alice()
        val post = client.post.create {
            title = "Written by Alice"
            author.connect { email eq "alice@acme.com" }
        }
        post.authorId shouldBe existing.id
    }

    @Test
    fun `connect on the other side points the existing rows at the new one`() {
        val orphanAuthor = client.user.create { email = "temp@acme.com" }
        val post = client.post.create {
            title = "Moves owner"
            authorId = orphanAuthor.id
        }
        val user = client.user.create {
            email = "alice@acme.com"
            posts.connect { id eq post.id }
        }
        client.post.findUnique { where { id eq post.id } }.shouldNotBeNull().authorId shouldBe user.id
    }

    @Test
    fun `connectOrCreate writes the row the first time and attaches it the second`() {
        val user = alice()
        val first = client.post.create {
            title = "One"
            authorId = user.id
            tags.connectOrCreate { name = "kotlin" }
        }
        val second = client.post.create {
            title = "Two"
            authorId = user.id
            tags.connectOrCreate { name = "kotlin" }
        }
        client.tag.count() shouldBe 1

        val posts = client.post.findMany {
            orderBy { id.asc() }
            include { tags { } }
        }
        posts.first { it.id == first.id }.tags.single().name shouldBe "kotlin"
        posts.first { it.id == second.id }.tags.single().name shouldBe "kotlin"
    }

    @Test
    fun `connectOrCreate says so when nothing it was given identifies a row`() {
        val user = alice()
        val thrown = runCatching {
            client.post.create {
                title = "One"
                authorId = user.id
                tags.connectOrCreate { /* nothing unique was set */ }
            }
        }.exceptionOrNull()
        thrown?.message.orEmpty() shouldContain "cannot tell which row to look for"
    }

    @Test
    fun `a nested write that fails takes the whole shape with it`() {
        client.user.create { email = "taken@acme.com" }
        val thrown = runCatching {
            client.user.create {
                email = "alice@acme.com"
                posts.create { title = "Would be orphaned" }
                profile.create { bio = "Also orphaned" }
                posts.connect { id eq 999 }
            }
        }.exceptionOrNull()

        thrown.shouldNotBeNull()
        client.user.count { where { email eq "alice@acme.com" } } shouldBe 0
        client.post.count() shouldBe 0
        client.profile.count() shouldBe 0
    }

    @Test
    fun `createMany refuses rows that bring relations with them`() {
        val thrown = runCatching {
            client.user.createMany {
                row {
                    email = "a@acme.com"
                    posts.create { title = "Nested" }
                }
            }
        }.exceptionOrNull()
        thrown?.message.orEmpty() shouldContain "writes its rows in one statement"
    }

    @Test
    fun `a many-to-many relation loads through its join table, from either side`() {
        val user = alice()
        val kotlin = client.tag.create { name = "kotlin" }
        val jvm = client.tag.create { name = "jvm" }
        val first = client.post.create {
            title = "Both tags"
            authorId = user.id
        }
        val second = client.post.create {
            title = "One tag"
            authorId = user.id
        }
        client.rawExecute(
            """insert into "_PostTags" ("A", "B") values (?, ?), (?, ?), (?, ?)""",
            listOf(first.id, kotlin.id, first.id, jvm.id, second.id, kotlin.id),
        )

        val posts = client.post.findMany {
            orderBy { id.asc() }
            include { tags { orderBy { name.asc() } } }
        }
        posts.first { it.id == first.id }.tags.map { it.name } shouldContainExactly listOf("jvm", "kotlin")
        posts.first { it.id == second.id }.tags.map { it.name } shouldContainExactly listOf("kotlin")

        val tags = client.tag.findMany {
            orderBy { name.asc() }
            include { posts { } }
        }
        tags.first { it.name == "jvm" }.posts.map { it.id } shouldContainExactly listOf(first.id)
        tags.first { it.name == "kotlin" }.posts.map { it.id }.sorted() shouldContainExactly listOf(first.id, second.id)
    }

    @Test
    fun `a many-to-many level costs two statements, whatever the number of rows`() {
        val counter = java.util.concurrent.atomic.AtomicInteger()
        val user = alice()
        val tag = client.tag.create { name = "kotlin" }
        repeat(4) { index ->
            val post = client.post.create {
                title = "Post $index"
                authorId = user.id
            }
            client.rawExecute("""insert into "_PostTags" ("A", "B") values (?, ?)""", listOf(post.id, tag.id))
        }

        countingClient(counter).use { counted ->
            counter.set(0)
            val posts = counted.post.findMany { include { tags { } } }
            posts.size shouldBe 4
            posts.all { it.tags.size == 1 } shouldBe true

            // The posts, the pairs, and the tags they point at.
            counter.get() shouldBe 3
        }
    }

    @Test
    fun `asking for a relation alongside a partial select is a question with no answer`() {
        alice()
        val thrown = runCatching {
            client.user.projectMany {
                select { email }
                include { posts { } }
            }
        }.exceptionOrNull()
        thrown?.message.orEmpty() shouldContain "nowhere to put the relations"
    }

    @Test
    fun `values are bound, so a quote in the data is just a quote`() {
        val user = client.user.create {
            email = "quote@acme.com"
            name = "'); drop table users; --"
        }
        client.user.findUnique { where { id eq user.id } }.shouldNotBeNull().name shouldBe "'); drop table users; --"
        client.user.count() shouldBe 1
    }

    @Test
    fun `aggregate works out every summary the block asked for in one statement`() {
        val author = alice()
        client.post.createMany {
            row {
                title = "A"
                views = 10
                draft = false
                authorId = author.id
            }
            row {
                title = "B"
                views = 30
                draft = false
                authorId = author.id
            }
            row {
                title = "C"
                views = 5
                draft = true
                authorId = author.id
            }
        }

        val counter = java.util.concurrent.atomic.AtomicInteger()
        val summary = countingClient(counter).post.aggregate {
            where { draft eq false }
            count()
            sum { views }
            average { views }
            minimum { views }
            maximum { title }
        }

        summary.count shouldBe 2
        summary.sumOfViews shouldBe java.math.BigDecimal("40")
        summary.averageOfViews shouldBe 20.0
        summary.minimumOfViews shouldBe 10
        summary.maximumOfTitle shouldBe "B"
        counter.get() shouldBe 1
    }

    @Test
    fun `summarising no rows tells them apart from summarising rows that add up to zero`() {
        val summary = client.post.aggregate {
            count()
            sum { views }
            maximum { title }
        }

        summary.count shouldBe 0
        summary.sumOfViews.shouldBeNull()
        summary.maximumOfTitle.shouldBeNull()
    }

    @Test
    fun `a summary over a mapped column addresses the column the schema mapped it to`() {
        val author = alice()
        val summary = client.user.aggregate { maximum { createdAt } }
        summary.maximumOfCreatedAt shouldBe author.createdAt
    }

    @Test
    fun `reading a summary this query never asked for says how to ask for it`() {
        alice()
        val summary = client.user.aggregate { count() }
        val failure = runCatching { summary.maximumOfCreatedAt }.exceptionOrNull()
        (failure is VolanAggregateNotAskedException) shouldBe true
        failure?.message.orEmpty() shouldContain "Ask for it in the `aggregate` block"
    }

    @Test
    fun `timestamps survive the round trip`() {
        val before = Instant.now().minusSeconds(1)
        val user = alice()
        (user.createdAt >= before) shouldBe true
        (user.updatedAt >= before) shouldBe true
    }

    /**
     * A client whose connections count the statements prepared on them.
     *
     * Proving the absence of N+1 means counting statements, and the honest place to count them is
     * where they are prepared. The proxies stand in for the two JDBC interfaces without reimplementing
     * their fifty-odd methods.
     */
    private fun countingClient(counter: java.util.concurrent.atomic.AtomicInteger): VolanClient {
        val loader = javaClass.classLoader
        val connections = DataSource::class.java
        val plain = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(connections)) { _, method, arguments ->
            val values = arguments ?: emptyArray()
            if (method.name != "getConnection") {
                return@newProxyInstance method.invoke(fallbackDataSource(), *values)
            }
            val connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
            java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(java.sql.Connection::class.java)) { _, call, callArguments ->
                if (call.name == "prepareStatement") counter.incrementAndGet()
                call.invoke(connection, *(callArguments ?: emptyArray()))
            }
        } as DataSource
        return VolanClient(
            Volan.builder()
                .tables(VolanClient.TABLES)
                .readers(VolanClient.READERS)
                .dialect(PostgresDialect)
                .dataSource(plain)
                .build(),
        )
    }

    private fun fallbackDataSource(): DataSource = throw UnsupportedOperationException(
        "the counting data source only hands out connections",
    )

    private fun readSingleValue(sql: String): String =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    result.next()
                    result.getString(1)
                }
            }
        }
}
