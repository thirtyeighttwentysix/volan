package verify

import com.example.sqlite.Role
import com.example.sqlite.VolanClient
import io.github.thirtyeighttwentysix.volan.Json
import io.github.thirtyeighttwentysix.volan.dialect.VolanDialectException
import io.github.thirtyeighttwentysix.volan.dialect.sqlite.SqliteDialect
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSync
import io.github.thirtyeighttwentysix.volan.migrate.SqliteReader
import io.github.thirtyeighttwentysix.volan.runtime.Isolation
import io.github.thirtyeighttwentysix.volan.runtime.Volan
import io.github.thirtyeighttwentysix.volan.runtime.VolanConstraintException
import io.github.thirtyeighttwentysix.volan.runtime.VolanForeignKeyException
import io.github.thirtyeighttwentysix.volan.runtime.VolanNotFoundException
import io.github.thirtyeighttwentysix.volan.runtime.VolanUniqueConstraintException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.readText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs on every CI OS, without Docker, using the client generated from sqlite.volan. */
class SqliteIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    private lateinit var client: VolanClient

    @BeforeEach
    fun start() {
        client = VolanClient.builder().url("jdbc:sqlite:${temporary.resolve("database.sqlite")}").build()
        initialize(client)
    }

    @AfterEach
    fun stop() {
        client.close()
    }

    @Test
    fun `CRUD returns defaults and mapped enums and reports a missing row`() {
        val user = client.user.create { email = "a@example.org" }
        user.role shouldBe Role.USER
        user.name.shouldBeNull()
        val changed = client.user.update {
            where { id eq user.id }
            data { name = "Alice"; role = Role.ADMIN }
        }
        changed.name shouldBe "Alice"
        changed.role shouldBe Role.ADMIN
        client.user.findUnique { where { email eq user.email } } shouldBe changed
        client.user.delete { where { id eq user.id } } shouldBe changed
        assertThrows<VolanNotFoundException> { client.user.delete { where { id eq user.id } } }
    }

    @Test
    fun `bulk writes preserve heterogeneous defaults and rollback all batches on conflict`() {
        client.user.createMany {
            row { email = "a@example.org" }
            row { email = "b@example.org"; name = "Bob"; role = Role.ADMIN }
            row { email = "c@example.org"; name = "Carol" }
        } shouldBe 3
        client.user.findMany { orderBy { id.asc() } }.map { it.role } shouldContainExactly
            listOf(Role.USER, Role.ADMIN, Role.USER)
        assertThrows<VolanUniqueConstraintException> {
            client.user.createMany {
                row { email = "new@example.org" }
                row { email = "a@example.org"; name = "duplicate" }
            }
        }
        client.user.count() shouldBe 3
        client.user.updateMany { where { name.isNull() }; data { name = "Unnamed" } } shouldBe 1
        client.user.deleteMany { where { role eq Role.ADMIN } } shouldBe 1
    }

    @Test
    fun `bulk writes chunk below parameter limits`() {
        client.user.createMany { repeat(1100) { index -> row { email = "batch$index@example.org" } } } shouldBe 1100
        client.user.count() shouldBe 1100
    }

    @Test
    fun `case sensitive and insensitive filters escape both glob and like wildcards`() {
        client.user.create { email = "Alice*?[%_\\]@example.org" }
        client.user.create { email = "alice-ordinary@example.org" }
        client.user.count { where { email startsWith "Alice" } } shouldBe 1
        client.user.count { where { email.ignoringCase() startsWith "ALICE" } } shouldBe 2
        client.user.count { where { email contains "*?[%_\\]" } } shouldBe 1
        client.user.count { where { email.ignoringCase() contains "%_\\]" } } shouldBe 1
        client.user.count { where { email endsWith "@example.org" } } shouldBe 2
    }

    @Test
    fun `offset without limit and null ordering are valid SQLite`() {
        client.user.createMany {
            row { email = "a@example.org"; name = "Zed" }
            row { email = "b@example.org" }
            row { email = "c@example.org"; name = "Amy" }
        }
        client.user.findMany { orderBy { id.asc() }; skip = 1 }.map { it.email } shouldContainExactly
            listOf("b@example.org", "c@example.org")
        client.user.findMany { orderBy { name.ascNullsLast() } }
            .map { it.name } shouldContainExactly listOf("Amy", "Zed", null)
    }

    @Test
    fun `nested writes and batched many to many includes use the same API`() {
        val user = client.user.create {
            email = "author@example.org"
            posts.create {
                title = "One"
                tags.create { name = "sqlite" }
            }
            posts.create { title = "Two" }
        }
        val loaded = client.user.findFirstOrThrow { include { posts { include { tags() }; orderBy { id.asc() } } } }
        loaded.posts.map { it.title } shouldContainExactly listOf("One", "Two")
        loaded.posts.first().tags.single().name shouldBe "sqlite"
        client.user.count { where { posts { some { title eq "One" } } } } shouldBe 1
        client.user.update {
            where { id eq user.id }
            data { posts.create { title = "Three" } }
        }
        client.post.count() shouldBe 3
        client.user.delete { where { id eq user.id } }
        client.post.count() shouldBe 0
        client.rawQuery("select count(*) as total from _PostTags", emptyList()) { it.getLong("total") }.single() shouldBe 0
    }

    @Test
    fun `composite cursors page by the complete key`() {
        val user = client.user.create { email = "author@example.org" }
        val post = client.post.create { title = "One"; authorId = user.id }
        client.comment.create { postId = post.id; authorId = user.id; body = "First" }
        val other = client.user.create { email = "other@example.org" }
        client.comment.create { postId = post.id; authorId = other.id; body = "Second" }
        client.comment.findMany { cursor(post.id, user.id) }.single().body shouldBe "Second"
    }

    @Test
    fun `aggregate groupBy and distinct projections have database semantics`() {
        val user = client.user.create { email = "author@example.org" }
        client.post.createMany {
            row { title = "One"; authorId = user.id; views = 3 }
            row { title = "Two"; authorId = user.id; views = 7; draft = false }
        }
        val summary = client.post.aggregate { count(); sum { views }; average { views } }
        summary.count shouldBe 2
        summary.sumOfViews shouldBe BigDecimal.TEN
        summary.averageOfViews shouldBe 5.0
        val groups = client.post.groupBy { by { authorId }; count(); having { count gt 1L } }
        groups.single().count shouldBe 2
        client.post.projectMany { select { draft }; distinct { draft } }.size shouldBe 2
        assertThrows<VolanDialectException> { client.post.findMany { distinct { draft } } }
    }

    @Test
    fun `supported scalar types round trip including nanosecond timestamp`() {
        val moment = Instant.parse("2026-10-01T10:20:30.123456789Z")
        val date = LocalDate.of(2026, 10, 1)
        val time = LocalTime.of(10, 20, 30, 123456789)
        val token = UUID.randomUUID()
        val row = client.scalars.create {
            ratio = 1.25f
            precise = 2.5
            enabled = true
            this.moment = moment
            this.date = date
            this.time = time
            this.token = token
            data = byteArrayOf(0, 1, -1)
            document = Json.of("{\"sqlite\":true}")
        }
        row.ratio shouldBe 1.25f
        row.precise shouldBe 2.5
        row.enabled shouldBe true
        row.moment shouldBe moment
        row.date shouldBe date
        row.time shouldBe time
        row.token shouldBe token
        row.data shouldBe byteArrayOf(0, 1, -1)
        row.document?.raw shouldBe "{\"sqlite\":true}"
        client.scalars.findFirstOrThrow { where { this.moment eq moment } }.id shouldBe row.id
        val empty = client.scalars.create { ratio = 0f; precise = 0.0; enabled = false }
        empty.moment.shouldBeNull()
        empty.date.shouldBeNull()
        empty.time.shouldBeNull()
        empty.token.shouldBeNull()
        empty.data.shouldBeNull()
        empty.document.shouldBeNull()
    }

    @Test
    fun `transactions rollback and nested savepoints keep the outer write`() {
        client.transaction(isolation = Isolation.SERIALIZABLE) { tx ->
            tx.user.create { email = "outer@example.org" }
            assertThrows<IllegalArgumentException> {
                tx.transaction { nested ->
                    nested.user.create { email = "inner@example.org" }
                    throw IllegalArgumentException("undo savepoint")
                }
            }
        }
        client.user.count() shouldBe 1
        assertThrows<IllegalStateException> {
            client.transaction { tx ->
                tx.user.create { email = "rolledback@example.org" }
                throw IllegalStateException("undo transaction")
            }
        }
        client.user.count() shouldBe 1
    }

    @Test
    fun `constraints translate to portable exceptions and cannot orphan rows`() {
        val user = client.user.create { email = "a@example.org" }
        assertThrows<VolanUniqueConstraintException> { client.user.create { email = user.email } }
        assertThrows<VolanForeignKeyException> { client.post.create { title = "Orphan"; authorId = -1 } }
        assertThrows<VolanConstraintException> { client.rawExecute("insert into Tag(name) values (null)") }
        client.rawQuery("pragma foreign_keys", emptyList()) { it.getInt("foreign_keys") }.single() shouldBe 1
    }

    @Test
    fun `in memory database survives pool borrows and asynchronous calls`() {
        val worker = Executors.newFixedThreadPool(2)
        try {
            VolanClient.builder().url("jdbc:sqlite::memory:").maxPoolSize(8).asyncExecutor(worker).build().use { memory ->
                initialize(memory)
                val user = memory.user.createAsync { email = "async@example.org" }.get(10, TimeUnit.SECONDS)
                memory.user.findFirstAsync().get(10, TimeUnit.SECONDS) shouldBe user
                memory.user.count() shouldBe 1
            }
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `supplied data source connections also enforce foreign keys`() {
        val source = SQLiteDataSource().apply { url = "jdbc:sqlite:${temporary.resolve("external.sqlite")}" }
        Volan.builder().url(source.url).dataSource(source).build().use { database ->
            database.rawQuery("pragma foreign_keys", emptyList()) { it.getInt("foreign_keys") }.single() shouldBe 1
        }
        source.connection.use { it.isClosed shouldBe false }
    }

    @Test
    fun `file database persists when the client is reopened`() {
        val user = client.user.create { email = "persisted@example.org" }
        client.close()
        client = VolanClient.builder().url("jdbc:sqlite:${temporary.resolve("database.sqlite")}").build()
        client.user.findFirstOrThrow() shouldBe user
    }

    @Test
    fun `upsert inserts and then updates the matching row`() {
        val first = client.user.upsert {
            where { email eq "upsert@example.org" }
            create { email = "upsert@example.org" }
            update { name = "Updated" }
        }
        val second = client.user.upsert {
            where { email eq first.email }
            create { email = first.email }
            update { name = "Updated" }
        }
        second.id shouldBe first.id
        second.name shouldBe "Updated"
        client.user.count() shouldBe 1
    }

    private fun initialize(database: VolanClient) {
        val schema = SchemaLoader.load("sqlite.volan", Path.of("schema/sqlite.volan").readText()).schemaOrThrow()
        val statements = DriverManager.getConnection("jdbc:sqlite::memory:").use {
            DatabaseSync(SqliteReader(), SqliteDialect).plan(it, schema).render(SqliteDialect)
        }
        statements.forEach { database.rawExecute(it) }
    }
}
