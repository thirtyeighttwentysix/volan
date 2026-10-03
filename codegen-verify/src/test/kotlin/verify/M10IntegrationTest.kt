package verify

import com.example.sqlite.VolanClient
import io.github.thirtyeighttwentysix.volan.coroutines.suspendQuery
import io.github.thirtyeighttwentysix.volan.coroutines.SuspendingQueryExecutor
import io.github.thirtyeighttwentysix.volan.runtime.CreateSpec
import io.github.thirtyeighttwentysix.volan.runtime.QuerySpec
import io.github.thirtyeighttwentysix.volan.runtime.Volan
import com.example.sqlite.UserRowMapper
import io.github.thirtyeighttwentysix.volan.dialect.h2.H2Dialect
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.micrometer.MicrometerQueryInterceptor
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSchema
import io.github.thirtyeighttwentysix.volan.migrate.SchemaDiffer
import io.github.thirtyeighttwentysix.volan.migrate.SchemaMapper
import io.github.thirtyeighttwentysix.volan.runtime.QueryContext
import io.github.thirtyeighttwentysix.volan.runtime.QueryInterceptor
import io.github.thirtyeighttwentysix.volan.runtime.VolanUniqueConstraintException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.readText

class M10IntegrationTest {
    private fun initialize(db: VolanClient) {
        val schema = SchemaLoader.load("h2.volan", Path.of("schema/sqlite.volan").readText().replace("\"sqlite\"", "\"h2\""))
            .schemaOrThrow()
        SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(schema)).render(H2Dialect).forEach { db.rawExecute(it) }
    }

    @Test
    fun `generated client supports coroutine CRUD summaries relations and transactions`() = runBlocking<Unit> {
        VolanClient.builder().url("jdbc:h2:mem:${UUID.randomUUID()}").build().use { db ->
            initialize(db)
            val created = db.suspendQuery { user.create { email = "coroutine@example.org" } }
            assertEquals(created, db.user.suspendQuery { findUnique { where { id eq created.id } } })
            assertTrue(db.suspendQuery { user.exists() })
            db.suspendQuery {
                transaction { tx ->
                    tx.post.create { title = "Coroutine"; authorId = created.id }
                    tx.user.update { where { id eq created.id }; data { name = "Updated" } }
                }
            }
            val loaded = db.suspendQuery { user.findFirstOrThrow { include { posts {} } } }
            assertEquals("Coroutine", loaded.posts.single().title)
            assertEquals("Updated", loaded.name)
            assertThrows<VolanUniqueConstraintException> {
                db.suspendQuery {
                    transaction { tx ->
                        tx.user.create { email = "rolled-back@example.org" }
                        tx.user.create { email = created.email }
                    }
                }
            }
            assertEquals(1L, db.suspendQuery { user.count() })
            assertThrows<IllegalStateException> {
                db.transaction { runBlocking { db.suspendQuery { user.count() } } }
            }
            assertEquals(1L, db.suspendQuery { user.deleteMany {} })
            assertFalse(db.suspendQuery { user.exists() })
        }
    }

    @Test
    fun `interceptors wrap physical SQL in order including relation reads and raw SQL`() {
        val events = CopyOnWriteArrayList<String>()
        fun interceptor(name: String) = object : QueryInterceptor {
            override fun <T> intercept(query: QueryContext, next: Supplier<T>): T {
                events.add("$name:before")
                try {
                    return next.get()
                } finally {
                    events.add("$name:after")
                }
            }
        }
        SimpleMeterRegistry().let { registry ->
            VolanClient.builder().url("jdbc:h2:mem:${UUID.randomUUID()}")
                .interceptor(interceptor("first"))
                .interceptor(interceptor("second"))
                .interceptor(MicrometerQueryInterceptor(registry)).build().use { db ->
                    initialize(db)
                    db.user.create { email = "metrics@example.org" }
                    events.clear()
                    registry.clear()
                    db.user.findMany { include { posts {} } }
                    assertEquals(listOf("first:before", "second:before", "second:after", "first:after").repeatTwice(), events)
                    assertEquals(2L, registry.get("volan.query").tag("outcome", "success").timer().count())
                    assertThrows<VolanUniqueConstraintException> { db.user.create { email = "metrics@example.org" } }
                    assertEquals(1L, registry.get("volan.query").tag("outcome", "error").timer().count())
                    db.rawExecute("UPDATE \"users\" SET \"name\" = ?", listOf("private"))
                    assertEquals(1L, registry.get("volan.query").tag("operation", "execute").timer().count())
                }
        }
    }

    private fun <T> List<T>.repeatTwice(): List<T> = this + this

    @Test
    fun `interceptor and mapper failures roll back transactions and release resources`() = runBlocking<Unit> {
        val rejectWrite = AtomicBoolean()
        val interceptor = object : QueryInterceptor {
            override fun <T> intercept(query: QueryContext, next: Supplier<T>): T {
                val result = next.get()
                if (query.sql.contains("INSERT INTO") && rejectWrite.getAndSet(false)) error("observer failure")
                return result
            }
        }
        VolanClient.builder().url("jdbc:h2:mem:${UUID.randomUUID()}").maxPoolSize(1).interceptor(interceptor).build().use { db ->
            initialize(db)
            rejectWrite.set(true)
            assertThrows<IllegalStateException> {
                db.suspendQuery { transaction { tx -> tx.user.create { email = "undo@example.org" } } }
            }
            assertEquals(0L, db.suspendQuery { user.count() })
            assertThrows<IllegalStateException> {
                db.suspendQuery { rawQuery("SELECT 1 AS result_value", emptyList()) { error("mapper failure") } }
            }
            assertEquals(1, db.suspendQuery { rawQuery("SELECT 1 AS result_value", emptyList()) { it.getInt("result_value") }.single() })
        }
    }

    @Test
    fun `description API facade reads and writes without a generated coroutine client`() = runBlocking<Unit> {
        Volan.builder().url("jdbc:h2:mem:${UUID.randomUUID()}")
            .tables(VolanClient.TABLES).readers(VolanClient.READERS).build().use { db ->
            initialize(VolanClient(db))
            val executor = SuspendingQueryExecutor(db.executor)
            val created = executor.create(CreateSpec("User", mapOf("email" to "description@example.org")), UserRowMapper)
            assertEquals(listOf(created), executor.findMany(QuerySpec("User"), UserRowMapper))
            assertEquals(created, executor.findFirst(QuerySpec("User"), UserRowMapper))
            assertEquals(1L, executor.count(QuerySpec("User")))
            assertTrue(executor.exists(QuerySpec("User")))
        }
    }
}
