package verify

import com.example.blog.Role
import com.example.blog.VolanClient
import io.github.thirtyeighttwentysix.volan.runtime.Isolation
import io.github.thirtyeighttwentysix.volan.runtime.VolanNotFoundException
import io.github.thirtyeighttwentysix.volan.runtime.VolanUniqueConstraintException
import io.github.thirtyeighttwentysix.volan.runtime.VolanUnsupportedException
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
    fun `loading a relation says it is not available yet rather than answering wrongly`() {
        alice()
        val thrown = runCatching { client.user.findMany { include { posts { } } } }.exceptionOrNull()
        (thrown is VolanUnsupportedException) shouldBe true
        thrown?.message.orEmpty() shouldContain "arrives in M5"
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
    fun `timestamps survive the round trip`() {
        val before = Instant.now().minusSeconds(1)
        val user = alice()
        (user.createdAt >= before) shouldBe true
        (user.updatedAt >= before) shouldBe true
    }

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
